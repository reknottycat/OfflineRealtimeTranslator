package com.bohanli.ruzhtranslator.asr

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.bohanli.ruzhtranslator.BuildConfig
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Sends one audio window at a time to the private LiteRT worker process. */
class QwenNpuAsrEngine(context: Context, private val model: File, private val vocab: File) {
    private data class Request(val id: Int, val result: CompletableDeferred<QwenNpuTranscript>)

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val replies = Messenger(Handler(Looper.getMainLooper(), ::receive))
    private val requests = Mutex()
    private var sequence = 0
    private var pending: Request? = null
    private var worker: Messenger? = null
    private var connection: ServiceConnection? = null
    private var ready = false
    private var closed = false

    suspend fun initialize(): Unit = requests.withLock {
        withContext(Dispatchers.Main.immediate) {
            check(!closed && connection == null) { "千问 NPU 已初始化或已关闭" }
            check(isRuntimePresent(appContext)) { "缺少此设备的千问 NPU 运行库" }
            val request = newRequest()
            val candidate = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    if (closed || connection !== this) return
                    worker = Messenger(service)
                    send(QwenNpuProtocol.INITIALIZE, request.id, Bundle().apply {
                        putString(QwenNpuProtocol.MODEL_PATH, model.absolutePath)
                        putString(QwenNpuProtocol.VOCAB_PATH, vocab.absolutePath)
                    })
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    if (connection === this) fail("千问 NPU 进程退出")
                }

                override fun onBindingDied(name: ComponentName) {
                    if (connection === this) fail("千问 NPU 连接失效")
                }

                override fun onNullBinding(name: ComponentName) {
                    if (connection === this) fail("无法连接千问 NPU 进程")
                }
            }
            try {
                check(appContext.bindService(Intent(appContext, QwenNpuAsrService::class.java),
                    candidate, Context.BIND_AUTO_CREATE)) { "无法启动千问 NPU 进程" }
                connection = candidate
                await(request, INITIALIZE_TIMEOUT_MS, "千问 NPU 初始化超时")
                check(!closed && worker != null) { "千问 NPU 初始化后连接中断" }
                ready = true
            } catch (failure: Exception) {
                close(failure)
                throw failure
            } finally {
                pending = null
            }
        }
    }

    internal suspend fun transcribe(samples: FloatArray, language: String): QwenNpuTranscript {
        require(samples.size in 1..QwenNpuProtocol.MAX_SAMPLES) { "千问 NPU 只接收 5 秒以内的语音" }
        require(language in QwenNpuProtocol.LANGUAGES) { "不支持的语音输入语种" }
        return requests.withLock {
            withContext(Dispatchers.Main.immediate) {
                check(ready && !closed) { "千问 NPU 未就绪" }
                val request = newRequest()
                try {
                    // Serialized requests fit in Binder's buffer: one 80,000-float window is 320 KB.
                    send(QwenNpuProtocol.TRANSCRIBE, request.id, Bundle().apply {
                        putFloatArray(QwenNpuProtocol.SAMPLES, samples)
                        putString(QwenNpuProtocol.LANGUAGE, language)
                    })
                    await(request, TRANSCRIBE_TIMEOUT_MS, "千问 NPU 识别超时")
                } catch (failure: Exception) {
                    close(failure)
                    throw failure
                } finally {
                    pending = null
                }
            }
        }
    }

    fun release() {
        val reason = CancellationException("千问 NPU 已关闭")
        if (Looper.myLooper() == Looper.getMainLooper()) close(reason) else mainHandler.post { close(reason) }
    }

    private fun newRequest() = Request(++sequence, CompletableDeferred()).also { pending = it }

    private suspend fun await(request: Request, timeoutMs: Long, error: String): QwenNpuTranscript =
        checkNotNull(withTimeoutOrNull(timeoutMs) { request.result.await() }) { error }

    private fun receive(message: Message): Boolean {
        val request = pending?.takeIf { !closed && it.id == message.arg1 } ?: return true
        when (message.what) {
            QwenNpuProtocol.RESULT -> request.result.complete(QwenNpuTranscript(
                message.data.getString(QwenNpuProtocol.TEXT).orEmpty(),
                message.data.getString(QwenNpuProtocol.LANGUAGE).orEmpty(),
            ))
            QwenNpuProtocol.ERROR -> fail(message.data.getString(QwenNpuProtocol.TEXT) ?: "千问 NPU 执行失败")
        }
        return true
    }

    private fun send(what: Int, id: Int, data: Bundle) {
        val destination = worker ?: return fail("千问 NPU 未连接")
        try {
            destination.send(Message.obtain(null, what, id, 0).apply {
                replyTo = replies
                this.data = data
            })
        } catch (_: RemoteException) {
            fail("千问 NPU 连接中断")
        }
    }

    private fun fail(message: String) = close(IllegalStateException(message))

    private fun close(failure: Exception) {
        if (closed) return
        closed = true
        ready = false
        pending?.result?.completeExceptionally(failure)
        worker = null
        val previous = connection
        connection = null
        if (previous != null) appContext.unbindService(previous)
    }

    companion object {
        private const val INITIALIZE_TIMEOUT_MS = 90_000L
        private const val TRANSCRIBE_TIMEOUT_MS = 30_000L

        fun isRuntimePresent(context: Context): Boolean =
            BuildConfig.QNN_ENABLED && QwenNpuRuntime.isPresent(context)
    }
}

internal object QwenNpuProtocol {
    const val INITIALIZE = 1
    const val TRANSCRIBE = 2
    const val RESULT = 3
    const val ERROR = 4
    const val MODEL_PATH = "modelPath"
    const val VOCAB_PATH = "vocabPath"
    const val SAMPLES = "samples"
    const val TEXT = "text"
    const val LANGUAGE = "language"
    const val MAX_SAMPLES = 80_000
    val LANGUAGES = setOf("auto", "ru", "zh", "en")
}
