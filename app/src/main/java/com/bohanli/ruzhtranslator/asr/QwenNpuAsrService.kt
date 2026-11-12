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
import java.io.File
import java.util.concurrent.Executors

/** Keeps LiteRT and Qualcomm's loader off the UI thread and outside the UI process. */
class QwenNpuAsrService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val commands = Messenger(Handler(Looper.getMainLooper(), ::receive))
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "qwen-npu") }
    private var client: Messenger? = null
    private var generation = 0
    private var busy = false
    private var ready = false
    private var destroyed = false
    // Accessed only by the serial executor, including during teardown.
    private var recognizer: QwenLiteRtRecognizer? = null

    override fun onBind(intent: Intent): IBinder = commands.binder

    override fun onUnbind(intent: Intent): Boolean {
        clear()
        return false
    }

    override fun onDestroy() {
        destroyed = true
        clear()
        executor.shutdown()
        super.onDestroy()
    }

    private fun receive(message: Message): Boolean {
        if (destroyed) return true
        val reply = message.replyTo ?: return true
        val id = message.arg1
        when (message.what) {
            QwenNpuProtocol.INITIALIZE -> {
                clear()
                client = reply
                val model = message.data.getString(QwenNpuProtocol.MODEL_PATH).orEmpty()
                val vocab = message.data.getString(QwenNpuProtocol.VOCAB_PATH).orEmpty()
                execute(id) {
                    val weights = modelFile(model)
                    val vocabulary = modelFile(vocab)
                    require(vocabulary.parentFile == weights.parentFile && vocabulary.name == "vocab.json" &&
                        weights.extension == "tflite") { "千问 NPU 模型与词表不匹配" }
                    val runtimeDirectory = QwenNpuRuntime.prepare(this)
                    recognizer = QwenLiteRtRecognizer(this, weights, vocabulary, runtimeDirectory)
                    Log.i(TAG, "Qwen LiteRT NPU encoder and decoder initialized")
                    QwenNpuTranscript("", "")
                }
            }
            QwenNpuProtocol.TRANSCRIBE -> {
                if (client?.binder != reply.binder) return true
                val samples = message.data.getFloatArray(QwenNpuProtocol.SAMPLES)
                val language = message.data.getString(QwenNpuProtocol.LANGUAGE)
                if (!ready || busy || samples == null || samples.size !in 1..QwenNpuProtocol.MAX_SAMPLES ||
                    language == null || language !in QwenNpuProtocol.LANGUAGES) {
                    send(id, QwenNpuProtocol.ERROR, "千问 NPU 未就绪或音频参数无效", "")
                } else execute(id) { checkNotNull(recognizer).transcribe(samples, language) }
            }
        }
        return true
    }

    private fun execute(id: Int, task: () -> QwenNpuTranscript) {
        val owner = generation
        busy = true
        executor.execute {
            val outcome = runCatching(task)
            mainHandler.post {
                if (owner == generation) {
                    busy = false
                    outcome.fold(
                        onSuccess = { transcript ->
                            ready = true
                            send(id, QwenNpuProtocol.RESULT, transcript.text, transcript.language)
                        },
                        onFailure = { failure ->
                            ready = false
                            Log.w(TAG, "Qwen LiteRT NPU worker failed", failure)
                            send(id, QwenNpuProtocol.ERROR, failure.message ?: "千问 NPU 执行失败", "")
                        },
                    )
                }
            }
        }
    }

    private fun modelFile(path: String): File {
        val file = File(path).canonicalFile
        val models = File(filesDir, "models").canonicalFile
        require(file.parentFile?.parentFile == models && file.isFile && file.length() > 0L) {
            "千问 NPU 模型必须安装在应用的模型目录中"
        }
        return file
    }

    private fun send(id: Int, what: Int, text: String, language: String) {
        val destination = client ?: return
        try {
            destination.send(Message.obtain(null, what, id, 0).apply {
                data = Bundle().apply {
                    putString(QwenNpuProtocol.TEXT, text)
                    putString(QwenNpuProtocol.LANGUAGE, language)
                }
            })
        } catch (_: RemoteException) {
            clear()
        }
    }

    private fun clear() {
        generation += 1
        client = null
        ready = false
        busy = false
        executor.execute {
            try {
                recognizer?.close()
            } catch (failure: Exception) {
                Log.w(TAG, "Could not close Qwen LiteRT NPU worker", failure)
            } finally {
                recognizer = null
            }
        }
    }

    private companion object { const val TAG = "QwenNpuAsrService" }
}
