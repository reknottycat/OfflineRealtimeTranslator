package com.bohanli.ruzhtranslator.asr

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.QnnConfig
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Private bound service registered in the app's :nemotron_qnn process. */
class NemotronQnnService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commands = Messenger(Handler(Looper.getMainLooper(), ::receive))
    private var client: Messenger? = null
    private var startup: Job? = null
    private var recognizer: MultilingualAsrManager? = null
    private var ready = false
    private var generation = 0
    private var activeSession = 0

    override fun onBind(intent: Intent): IBinder = commands.binder

    override fun onUnbind(intent: Intent): Boolean {
        clear()
        return false
    }

    override fun onDestroy() {
        clear()
        scope.cancel()
        super.onDestroy()
    }

    private fun receive(message: Message): Boolean {
        if (message.what == NemotronQnnProtocol.INITIALIZE) {
            val reply = message.replyTo ?: return true
            initialize(reply, message.data.getString(NemotronQnnProtocol.MODEL_PATH).orEmpty(),
                message.data.getString(NemotronQnnProtocol.LANGUAGE).orEmpty())
            return true
        }
        if (message.replyTo?.binder != client?.binder || !ready) return true
        when (message.what) {
            NemotronQnnProtocol.START -> if (activeSession == 0 && message.arg1 > 0) {
                activeSession = message.arg1
                recognizer?.startListening()
            }
            NemotronQnnProtocol.STOP -> if (activeSession == message.arg1) recognizer?.stopListening()
        }
        return true
    }

    private fun initialize(reply: Messenger, modelPath: String, language: String) {
        clear()
        client = reply
        val owner = generation
        startup = scope.launch {
            try {
                val libraryDirectory = checkNotNull(QnnNemotronAsrEngine.runtimeDirectory(this@NemotronQnnService)) {
                    "缺少此骁龙平台的 QNN 运行库"
                }
                val modelDirectory = File(modelPath).canonicalFile
                require(modelDirectory.isDirectory && modelDirectory.parentFile == File(filesDir, "models").canonicalFile) {
                    "Nemotron QNN 模型必须安装在应用的模型目录中"
                }
                val engine = MultilingualAsrManager(
                    this@NemotronQnnService, modelDirectory, MultilingualAsrCatalog.NEMOTRON, language,
                    { text -> if (generation == owner && activeSession != 0) send(NemotronQnnProtocol.PARTIAL, text) },
                    { text, detected -> if (generation == owner && activeSession != 0) send(NemotronQnnProtocol.FINAL, text, detected) },
                    { text -> if (generation == owner && activeSession != 0) send(NemotronQnnProtocol.ERROR, text) },
                    {
                        if (generation == owner && activeSession != 0) {
                            send(NemotronQnnProtocol.IDLE)
                            activeSession = 0
                        }
                    },
                    onlineRecognizerFactory = { createRecognizer(modelDirectory, libraryDirectory) },
                )
                recognizer = engine
                engine.initialize()
                if (generation == owner) {
                    ready = true
                    Log.i(TAG, "Nemotron QNN HTP recognizer initialized on ${QnnNemotronAsrEngine.chipset()}")
                    send(NemotronQnnProtocol.READY)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation == owner) {
                    recognizer?.release()
                    recognizer = null
                    send(NemotronQnnProtocol.ERROR, failure.message ?: "Nemotron QNN 初始化失败")
                }
            }
        }
    }

    private fun createRecognizer(modelDirectory: File, libraryDirectory: File): OnlineRecognizer {
        val files = listOf("encoder.bin", "decoder.bin", "joiner.bin", "tokens.txt").map { File(modelDirectory, it) }
        require(files.all { it.isFile && it.length() > 0L }) { "Nemotron QNN 模型文件不完整" }
        OnlineRecognizer.prependAdspLibraryPath(libraryDirectory.absolutePath)
        return OnlineRecognizer(config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(qnnConfig = QnnConfig(
                    backendLib = File(libraryDirectory, "libQnnHtp.so").absolutePath,
                    systemLib = File(libraryDirectory, "libQnnSystem.so").absolutePath,
                    contextBinary = files.take(3).joinToString(",") { it.absolutePath },
                )),
                tokens = files.last().absolutePath,
                provider = "qnn",
                modelType = "nemo_transducer",
                numThreads = 1,
            ),
        ))
    }

    private fun send(what: Int, text: String = "", language: String = "") {
        val destination = client ?: return
        val message = Message.obtain(null, what, activeSession, 0).apply {
            data = Bundle().apply {
                putString(NemotronQnnProtocol.TEXT, text)
                putString(NemotronQnnProtocol.LANGUAGE, language)
            }
        }
        try {
            destination.send(message)
        } catch (_: RemoteException) {
            clear()
        }
    }

    private fun clear() {
        generation += 1
        ready = false
        activeSession = 0
        client = null
        startup?.cancel()
        startup = null
        recognizer?.release()
        recognizer = null
    }

    private companion object {
        const val TAG = "NemotronQnnService"
    }
}
