package com.bohanli.ruzhtranslator.asr

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.bohanli.ruzhtranslator.BuildConfig
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Keeps the QNN native runtime, including its fatal errors, outside the UI process. */
class QnnNemotronAsrEngine(
    context: Context,
    private val modelDirectory: File,
    private val language: String,
    private val onPartial: (String) -> Unit,
    private val onFinalResult: (String, String) -> Unit,
    private val onError: (String) -> Unit,
    private val onIdle: () -> Unit = {},
) : AsrEngine {
    companion object {
        private const val INITIALIZE_TIMEOUT_MS = 45_000L
        private const val STOP_TIMEOUT_MS = 15_000L

        fun chipset(): String? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
            return AsrAccelerationPolicy.chipset(Build.VERSION.SDK_INT, Build.SOC_MODEL)
        }

        fun isRuntimePresent(context: Context): Boolean = runtimeDirectory(context) != null

        internal fun runtimeDirectory(context: Context): File? {
            if (!BuildConfig.QNN_ENABLED) return null
            val version = when (chipset()) {
                "SM8750" -> 79
                "SM8850" -> 81
                else -> return null
            }
            val directory = File(context.applicationInfo.nativeLibraryDir)
            return directory.takeIf {
                listOf("libQnnHtp.so", "libQnnSystem.so", "libQnnHtpV${version}Stub.so",
                    "libQnnHtpV${version}Skel.so").all { name -> File(it, name).isFile }
            }
        }
    }

    private enum class State { NEW, INITIALIZING, READY, RECORDING, STOPPING, FAILED, RELEASED }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val replies = Messenger(Handler(Looper.getMainLooper(), ::receive))
    private val stopTimeout = Runnable {
        if (state == State.STOPPING) fail("Nemotron NPU 完成最后一段识别超时")
    }
    @Volatile private var state = State.NEW
    private var connection: ServiceConnection? = null
    private var worker: Messenger? = null
    private var initialization: CompletableDeferred<Unit>? = null
    private var session = 0
    private var sessionError: String? = null

    /** False after a transport failure, even if the recognizer initialized earlier. */
    val isUsable: Boolean get() = when (state) {
        State.READY, State.RECORDING, State.STOPPING -> true
        else -> false
    }

    override suspend fun initialize(): Unit = withContext(Dispatchers.Main.immediate) {
        check(state == State.NEW) { "Nemotron NPU 已初始化或已释放" }
        check(isRuntimePresent(appContext)) { "此 APK 或设备缺少匹配的 Qualcomm QNN 运行库" }
        val result = CompletableDeferred<Unit>()
        initialization = result
        state = State.INITIALIZING
        val candidate = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (connection !== this || state != State.INITIALIZING) return
                worker = Messenger(service)
                send(NemotronQnnProtocol.INITIALIZE, Bundle().apply {
                    putString(NemotronQnnProtocol.MODEL_PATH, modelDirectory.absolutePath)
                    putString(NemotronQnnProtocol.LANGUAGE, language)
                })
            }

            override fun onServiceDisconnected(name: ComponentName) {
                if (connection === this) fail("Nemotron NPU 进程退出，请重新打开语音识别")
            }

            override fun onBindingDied(name: ComponentName) {
                if (connection === this) fail("Nemotron NPU 进程连接失效")
            }

            override fun onNullBinding(name: ComponentName) {
                if (connection === this) fail("无法连接 Nemotron NPU 进程")
            }
        }
        var initialized = false
        try {
            val intent = Intent(appContext, NemotronQnnService::class.java)
            check(appContext.bindService(intent, candidate, Context.BIND_AUTO_CREATE)) {
                "无法启动 Nemotron NPU 进程"
            }
            connection = candidate
            check(withTimeoutOrNull(INITIALIZE_TIMEOUT_MS) { result.await(); true } == true) {
                "Nemotron NPU 初始化超时"
            }
            check(state == State.READY) { "Nemotron NPU 初始化后连接中断" }
            initialized = true
        } finally {
            initialization = null
            if (!initialized && state != State.RELEASED) {
                state = State.FAILED
                disconnect()
            }
        }
    }

    override fun startListening() = onMain {
        if (state == State.READY) {
            session += 1
            sessionError = null
            state = State.RECORDING
            send(NemotronQnnProtocol.START)
        } else if (state != State.RELEASED) {
            onError("Nemotron NPU 未就绪，请重新打开语音识别")
        }
    }

    override fun stopListening() = onMain {
        if (state == State.RECORDING) {
            state = State.STOPPING
            mainHandler.postDelayed(stopTimeout, STOP_TIMEOUT_MS)
            send(NemotronQnnProtocol.STOP)
        }
    }

    override fun hasPendingResults(): Boolean = state == State.RECORDING || state == State.STOPPING

    override fun release() = onMain {
        state = State.RELEASED
        initialization?.cancel()
        disconnect()
    }

    private fun receive(message: Message): Boolean {
        if (state == State.RELEASED || state == State.FAILED) return true
        if (state == State.INITIALIZING) {
            when (message.what) {
                NemotronQnnProtocol.READY -> {
                    state = State.READY
                    initialization?.complete(Unit)
                }
                NemotronQnnProtocol.ERROR -> fail(message.data.getString(NemotronQnnProtocol.TEXT)
                    ?: "Nemotron NPU 初始化失败")
            }
            return true
        }
        if (message.arg1 != session || (state != State.RECORDING && state != State.STOPPING)) return true
        val text = message.data.getString(NemotronQnnProtocol.TEXT).orEmpty()
        when (message.what) {
            NemotronQnnProtocol.PARTIAL -> onPartial(text)
            NemotronQnnProtocol.FINAL -> onFinalResult(text, message.data.getString(NemotronQnnProtocol.LANGUAGE).orEmpty())
            NemotronQnnProtocol.ERROR -> sessionError = text
            NemotronQnnProtocol.IDLE -> {
                mainHandler.removeCallbacks(stopTimeout)
                state = State.READY
                onIdle()
                val failure = sessionError
                sessionError = null
                if (failure != null) onError(failure)
            }
        }
        return true
    }

    private fun send(what: Int, data: Bundle? = null) {
        val destination = worker ?: return fail("Nemotron NPU 未连接")
        val message = Message.obtain(null, what, session, 0).apply {
            replyTo = replies
            if (data != null) this.data = data
        }
        try {
            destination.send(message)
        } catch (_: RemoteException) {
            fail("Nemotron NPU 进程连接中断")
        }
    }

    private fun fail(message: String) {
        if (state == State.FAILED || state == State.RELEASED) return
        val wasInitializing = state == State.INITIALIZING
        state = State.FAILED
        disconnect()
        if (wasInitializing) {
            initialization?.completeExceptionally(IllegalStateException(message))
        } else mainHandler.post {
            if (state == State.FAILED) {
                onIdle()
                onError(message)
            }
        }
    }

    private fun disconnect() {
        mainHandler.removeCallbacks(stopTimeout)
        worker = null
        val previous = connection
        connection = null
        if (previous != null) appContext.unbindService(previous)
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post { block() }
    }
}

internal object NemotronQnnProtocol {
    const val INITIALIZE = 1
    const val START = 2
    const val STOP = 3
    const val READY = 4
    const val PARTIAL = 5
    const val FINAL = 6
    const val ERROR = 7
    const val IDLE = 8
    const val MODEL_PATH = "modelPath"
    const val LANGUAGE = "language"
    const val TEXT = "text"
}
