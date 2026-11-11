package com.bohanli.ruzhtranslator.asr

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fully offline, real Android ASR backend using the officially published
 * sherpa-onnx 2.0.1 JNI/AAR and a pinned GigaAM v2 Russian CTC INT8 ONNX file.
 *
 * This is an OFFLINE recognizer. "Live" partials are simulated by repeatedly
 * decoding an utterance buffer; final text is emitted at a silence endpoint
 * or after 12 seconds. They are not frame-level streaming recognizer outputs.
 */
class GigaAmAsrManager(
    private val appContext: Context,
    private val modelDirectory: File,
    private val onPartial: (String) -> Unit,
    private val onFinalResult: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onIdle: () -> Unit = {},
    private val modelId: String = GigaAmCatalog.MODEL_ID,
    private val onDetected: ((String, String?) -> Unit)? = null
) : AsrEngine {
    companion object {
        private const val TAG = "GigaAmAsrManager"
        private const val SAMPLE_RATE = 16_000
        private const val MAX_SAMPLES = SAMPLE_RATE * 12
        private const val MIN_UTTERANCE = SAMPLE_RATE / 2
        private const val END_SILENCE = SAMPLE_RATE * 8 / 10
        private const val PARTIAL_INTERVAL = SAMPLE_RATE * 3
    }

    private data class DecodeRequest(val samples: FloatArray, val final: Boolean, val epoch: Int)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val jobs = Channel<DecodeRequest>(Channel.UNLIMITED)
    private val epoch = AtomicInteger(0)
    private val decoderBusy = AtomicBoolean(false)
    private val pendingDecode = AtomicInteger(0)
    @Volatile private var running = false
    @Volatile private var shuttingDown = false
    @Volatile private var currentAudio: AudioRecord? = null
    private var captureJob: Job? = null
    private var decodeJob: Job? = null
    private var recognizer: OfflineRecognizer? = null

    override suspend fun initialize(): Unit = withContext(Dispatchers.IO) {
        try {
            val config = if (modelId == AsrBundleCatalog.QWEN.id) {
                val qwen = AsrBundleCatalog.QWEN
                check(AsrBundleDownloader(appContext, qwen).installed()) {
                    "Qwen3-ASR ONNX 模型文件未下载完整"
                }
                val qwenConfig = OfflineQwen3AsrModelConfig(
                    convFrontend = File(modelDirectory, "conv_frontend.onnx").absolutePath,
                    encoder = File(modelDirectory, "encoder.int8.onnx").absolutePath,
                    decoder = File(modelDirectory, "decoder.int8.onnx").absolutePath,
                    tokenizer = File(modelDirectory, "tokenizer").absolutePath,
                    maxTotalLen = 512,
                    maxNewTokens = 128
                )
                OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        qwen3Asr = qwenConfig, numThreads = 2, provider = "cpu")
                )
            } else {
                val model = File(modelDirectory, GigaAmCatalog.MODEL_FILE)
                require(model.isFile && model.length() == GigaAmCatalog.MODEL_BYTES) {
                    "GigaAM ONNX 文件不存在或大小不符，请在设置中下载已核验版本"
                }
                val tokens = File(modelDirectory, GigaAmCatalog.TOKENS_FILE)
                if (!tokens.exists()) {
                    appContext.assets.open(GigaAmCatalog.TOKENS_ASSET).use { source ->
                        tokens.outputStream().use { target -> source.copyTo(target) }
                    }
                }
                require(tokens.isFile && tokens.readLines().size == 34) {
                    "GigaAM 俄语 tokenizer 未正确安装"
                }
                OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        nemo = OfflineNemoEncDecCtcModelConfig(model = model.absolutePath),
                        tokens = tokens.absolutePath, modelType = "nemo_ctc",
                        numThreads = 2, provider = "cpu")
                )
            }
            recognizer = OfflineRecognizer(config = config)
            decodeJob = scope.launch { decodeLoop() }
            Log.i(TAG, "Sherpa offline ASR CPU ready, model=" + modelId)
        } catch (failure: Exception) {
            Log.e(TAG, "GigaAM initialization failed", failure)
            withContext(Dispatchers.Main) {
                onError("GigaAM v2 初始化失败：" + (failure.message ?: failure.javaClass.simpleName))
            }
            throw failure
        }
        Unit
    }

    override fun hasPendingResults(): Boolean =
        (captureJob?.isActive == true) || pendingDecode.get() > 0

    override fun startListening() {
        if (shuttingDown || recognizer == null) {
            scope.launch(Dispatchers.Main) { onError("GigaAM 引擎未就绪，请在设置中下载模型") }
            return
        }
        if (hasPendingResults()) {
            scope.launch(Dispatchers.Main) {
                onError("上一段 GigaAM 还在完成最终识别，字幕已排队保存，请稍后继续")
            }
            return
        }
        running = true
        captureJob = scope.launch { recordLoop(epoch.incrementAndGet()) }.also { active ->
            active.invokeOnCompletion { notifyIdleIfReady() }
        }
    }

    private fun notifyIdleIfReady() {
        if (!shuttingDown && !running && !hasPendingResults()) {
            scope.launch(Dispatchers.Main) { if (!shuttingDown) onIdle() }
        }
    }

    private suspend fun sendFinal(samples: FloatArray, session: Int) {
        pendingDecode.incrementAndGet()
        try { jobs.send(DecodeRequest(samples, true, session)) }
        catch (failure: Throwable) {
            pendingDecode.decrementAndGet()
            throw failure
        }
    }

    private fun queuePartial(samples: FloatArray, session: Int) {
        // Running offline ASR multiple times concurrently adds latency rather than value.
        if (pendingDecode.get() > 0 || decoderBusy.get()) return
        pendingDecode.incrementAndGet()
        if (jobs.trySend(DecodeRequest(samples, false, session)).isFailure) {
            pendingDecode.decrementAndGet()
        }
    }

    override fun stopListening() {
        running = false
        try { currentAudio?.stop() } catch (failure: Exception) {
            Log.d(TAG, "AudioRecord stop: " + failure.message)
        }
        // Do not cancel the decoder: the last finalized text must reach the
        // existing subtitle autosave/translation pipeline after Stop.
    }

    override fun release() {
        if (shuttingDown) return
        shuttingDown = true
        stopListening()
        captureJob?.cancel()
        jobs.close()
        scope.launch {
            try { decodeJob?.join() } finally {
                try { recognizer?.release() } catch (failure: Exception) {
                    Log.w(TAG, "ASR release: " + failure.message)
                }
                recognizer = null
                scope.cancel()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun recordLoop(session: Int) {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            withContext(Dispatchers.Main) { onError("没有麦克风权限") }
            return
        }
        val minimum = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) {
            withContext(Dispatchers.Main) { onError("不支持 16kHz 单声道录音") }
            return
        }
        val buffer = ShortArray(maxOf(3200, minimum / 2))
        val recorder = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimum * 4, 32_000))
        } catch (failure: SecurityException) {
            withContext(Dispatchers.Main) { onError("麦克风权限被撤销") }
            return
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            withContext(Dispatchers.Main) { onError("GigaAM 录音器初始化失败") }
            return
        }
        currentAudio = recorder
        val utterance = FloatArray(MAX_SAMPLES)
        var count = 0
        var voicedSamples = 0
        var silenceSamples = 0
        var sincePartial = 0
        try {
            recorder.startRecording()
            while (running && !shuttingDown) {
                val size = recorder.read(buffer, 0, buffer.size)
                if (size < 0) break
                if (size == 0) continue
                var energy = 0.0
                for (i in 0 until size) {
                    val sample = buffer[i].toDouble() / 32768.0
                    energy += sample * sample
                }
                val voiced = kotlin.math.sqrt(energy / size) > 0.010
                if (voiced) {
                    voicedSamples += size
                    silenceSamples = 0
                } else if (count > 0) {
                    silenceSamples += size
                }
                if (voiced || count > 0) {
                    for (i in 0 until size) {
                        if (count >= utterance.size) break
                        utterance[count++] = buffer[i].toFloat() / 32768f
                    }
                    sincePartial += size
                }
                val endpoint = count >= MAX_SAMPLES ||
                    (count >= SAMPLE_RATE && silenceSamples >= END_SILENCE)
                if (endpoint) {
                    if (voicedSamples >= MIN_UTTERANCE) {
                        sendFinal(utterance.copyOf(count), session)
                    }
                    count = 0
                    voicedSamples = 0
                    silenceSamples = 0
                    sincePartial = 0
                } else if (voicedSamples >= MIN_UTTERANCE &&
                    count >= SAMPLE_RATE * 2 && sincePartial >= PARTIAL_INTERVAL &&
                    !decoderBusy.get()) {
                    sincePartial = 0
                    queuePartial(utterance.copyOf(count), session)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (running && !shuttingDown) {
                withContext(Dispatchers.Main) {
                    onError("GigaAM 录音异常：" + (failure.message ?: "请重新开始"))
                }
            }
        } finally {
            if (!shuttingDown && voicedSamples >= MIN_UTTERANCE && count > 0) {
                pendingDecode.incrementAndGet()
                if (jobs.trySend(DecodeRequest(utterance.copyOf(count), true, session)).isFailure) {
                    pendingDecode.decrementAndGet()
                }
            }
            try { recorder.stop() } catch (_: Exception) {}
            recorder.release()
            currentAudio = null
            running = false
        }
    }

    private suspend fun decodeLoop() {
        val worker = recognizer ?: return
        for (request in jobs) {
            if (request.epoch != epoch.get() || shuttingDown) {
                pendingDecode.decrementAndGet()
                notifyIdleIfReady()
                continue
            }
            decoderBusy.set(true)
            try {
                val stream = worker.createStream()
                val recognized = try {
                    stream.acceptWaveform(request.samples, SAMPLE_RATE)
                    worker.decode(stream)
                    worker.getResult(stream)
                } finally { stream.release() }
                val result = recognized.text.trim()
                val detected = recognized.lang
                withContext(Dispatchers.Main) {
                    if (!shuttingDown && request.epoch == epoch.get()) {
                        if (request.final) {
                            onPartial("")
                            if (result.isNotBlank()) {
                                val handler = onDetected
                                if (handler != null) handler(result, detected)
                                else onFinalResult(result)
                            }
                        } else {
                            onPartial(result)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.e(TAG, "GigaAM decode failed", failure)
                withContext(Dispatchers.Main) {
                    onError("GigaAM 解码失败：" + (failure.message ?: "模型运行错误"))
                }
            } finally {
                decoderBusy.set(false)
                pendingDecode.decrementAndGet()
                notifyIdleIfReady()
            }
        }
    }
}
