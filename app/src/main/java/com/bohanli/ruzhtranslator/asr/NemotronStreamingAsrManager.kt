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
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Actual sherpa-onnx Nemotron 3.5 streaming RNNT (560 ms INT8) on Android CPU.
 * The model is multilingual but current sherpa Java result does not expose a
 * language ID; never silently label an unknown transcript "Russian".
 */
class NemotronStreamingAsrManager(
    private val context: Context,
    private val modelDir: File,
    private val selectedLanguage: String,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String, String?) -> Unit,
    private val onError: (String) -> Unit,
    private val onIdle: () -> Unit = {}
) : AsrEngine {
    companion object {
        private const val TAG = "NemotronStreamingASR"
        private const val RATE = 16000
    }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var running = false
    @Volatile private var closed = false
    @Volatile private var recorder: AudioRecord? = null
    private var job: Job? = null
    private var recognizer: OnlineRecognizer? = null

    override suspend fun initialize(): Unit = withContext(Dispatchers.IO) {
        val bundle = AsrBundleCatalog.NEMOTRON
        check(AsrBundleDownloader(context, bundle).installed()) {
            "Nemotron 3.5 INT8 模型不完整，请在设置中下载全部 ONNX 文件"
        }
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(modelDir, "encoder.int8.onnx").absolutePath,
                    decoder = File(modelDir, "decoder.int8.onnx").absolutePath,
                    joiner = File(modelDir, "joiner.int8.onnx").absolutePath
                ),
                tokens = File(modelDir, "tokens.txt").absolutePath,
                numThreads = 2,
                provider = "cpu",
                modelType = "transducer"
            ),
            enableEndpoint = true
        )
        recognizer = OnlineRecognizer(config = config)
        Log.i(TAG, "Nemotron 3.5 streaming CPU model initialized")
        Unit
    }

    override fun hasPendingResults(): Boolean = job?.isActive == true

    override fun startListening() {
        if (closed || recognizer == null) {
            scope.launch(Dispatchers.Main) { onError("Nemotron 模型尚未正确加载") }
            return
        }
        if (job?.isActive == true) return
        running = true
        job = scope.launch {
            try { recordLoop() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                Log.e(TAG, "Nemotron microphone/decode failed", failure)
                withContext(Dispatchers.Main) {
                    onError("Nemotron 流式识别失败：" + (failure.message ?: "未知错误"))
                }
            }
            finally {
                running = false
                withContext(Dispatchers.Main) { if (!closed) onIdle() }
            }
        }
    }

    override fun stopListening() {
        running = false
        try { recorder?.stop() } catch (_: Exception) {}
    }

    override fun release() {
        if (closed) return
        closed = true
        stopListening()
        job?.cancel()
        scope.launch {
            try { job?.join() } finally {
                recognizer?.release()
                recognizer = null
                scope.cancel()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun recordLoop() {
        val recognizer = recognizer ?: return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) error("麦克风权限未授予")
        val minimum = AudioRecord.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "不支持 16 kHz 单声道录音" }
        val source = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(32_000, minimum * 4))
        if (source.state != AudioRecord.STATE_INITIALIZED) {
            source.release()
            error("AudioRecord 初始化失败")
        }
        recorder = source
        val stream = recognizer.createStream()
        val lang = when (selectedLanguage) {
            "ru" -> "ru-RU"
            "zh" -> "zh-CN"
            "en" -> "en-US"
            else -> "auto"
        }
        stream.setOption("language", lang)
        val samples = ShortArray(2560)
        var previous = ""
        var finalized = ""
        try {
            source.startRecording()
            while (running && !closed) {
                val count = source.read(samples, 0, samples.size)
                if (count < 0) break
                if (count == 0) continue
                val data = FloatArray(count) { samples[it].toFloat() / 32768f }
                stream.acceptWaveform(data, RATE)
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                val transcript = recognizer.getResult(stream).text.trim()
                if (transcript.isNotBlank() && transcript != previous) {
                    previous = transcript
                    withContext(Dispatchers.Main) {
                        if (!closed) onPartial(transcript)
                    }
                }
                if (recognizer.isEndpoint(stream) && transcript.isNotBlank()) {
                    withContext(Dispatchers.Main) {
                        if (!closed) {
                            onPartial("")
                            onFinal(transcript, if (lang == "auto") null else lang)
                        }
                    }
                    finalized = transcript
                    previous = ""
                    recognizer.reset(stream)
                }
            }
            stream.inputFinished()
            while (recognizer.isReady(stream)) recognizer.decode(stream)
            val last = recognizer.getResult(stream).text.trim()
            if (!closed && last.isNotBlank() && last != finalized) {
                withContext(Dispatchers.Main) {
                    onPartial("")
                    onFinal(last, if (lang == "auto") null else lang)
                }
            }
        } finally {
            try { source.stop() } catch (_: Exception) {}
            source.release()
            recorder = null
            stream.release()
        }
    }
}
