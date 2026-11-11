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
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Delivers microphone results on the main thread. Stop drains the last utterance. */
class MultilingualAsrManager(
    private val appContext: Context,
    private val modelDirectory: File,
    private val modelId: String,
    private val language: String,
    private val onPartial: (String) -> Unit,
    private val onFinalResult: (String, String) -> Unit,
    private val onError: (String) -> Unit,
    private val onIdle: () -> Unit = {},
    private val onlineRecognizerFactory: (() -> OnlineRecognizer)? = null
) : AsrEngine {
    private companion object {
        const val TAG = "MultilingualAsr"
        const val SAMPLE_RATE = 16_000
        const val BLOCK_SAMPLES = SAMPLE_RATE / 10
        const val QUEUE_BLOCKS = 60
        const val QWEN_MAX_SAMPLES = SAMPLE_RATE * 12
        const val QWEN_END_SILENCE = SAMPLE_RATE * 7 / 10
        const val QWEN_MIN_SPEECH = SAMPLE_RATE / 10
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lifecycle = Any()
    @Volatile private var recording = false
    @Volatile private var released = false
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var activeSession: Job? = null
    private var online: OnlineRecognizer? = null
    private var offline: OfflineRecognizer? = null

    override suspend fun initialize(): Unit = withContext(Dispatchers.IO) {
        require(language in setOf("auto", "ru", "zh", "en")) { "不支持的语音输入语种" }
        when (modelId) {
            MultilingualAsrCatalog.NEMOTRON -> {
                val next = onlineRecognizerFactory?.invoke() ?: createNemotron()
                synchronized(lifecycle) {
                    if (released || online != null || offline != null) {
                        next.release()
                        error("语音识别引擎已关闭或重复初始化")
                    }
                    online = next
                }
            }
            MultilingualAsrCatalog.QWEN -> {
                val next = createQwen()
                synchronized(lifecycle) {
                    if (released || online != null || offline != null) {
                        next.release()
                        error("语音识别引擎已关闭或重复初始化")
                    }
                    offline = next
                }
            }
            else -> error("未知的多语种语音识别模型")
        }
    }

    private fun createNemotron() = OnlineRecognizer(
        config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = requireModelFile("encoder.int8.onnx"),
                    decoder = requireModelFile("decoder.int8.onnx"),
                    joiner = requireModelFile("joiner.int8.onnx")
                ),
                tokens = requireModelFile("tokens.txt"),
                numThreads = 2,
                modelType = "nemo_transducer"
            )
        )
    )

    private fun createQwen(): OfflineRecognizer {
        val tokenizerDirName = requireNotNull(MultilingualAsrCatalog.forId(modelId)?.tokenizerDirectory)
        val tokenizer = File(modelDirectory.parentFile, tokenizerDirName)
        require(listOf("vocab.json", "merges.txt", "tokenizer_config.json").all { name ->
            File(tokenizer, name).let { it.isFile && it.length() > 0L }
        }) { "千问 ASR tokenizer 未安装" }
        return OfflineRecognizer(
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 128),
                modelConfig = OfflineModelConfig(
                    qwen3Asr = OfflineQwen3AsrModelConfig(
                        convFrontend = requireModelFile("conv_frontend.onnx"),
                        encoder = requireModelFile("encoder.int8.onnx"),
                        decoder = requireModelFile("decoder.int8.onnx"),
                        tokenizer = tokenizer.absolutePath
                    ),
                    numThreads = 2
                )
            )
        )
    }

    private fun requireModelFile(name: String): String {
        val file = File(modelDirectory, name)
        require(file.isFile && file.length() > 0L) { "缺少语音模型文件 $name，请到设置中下载" }
        return file.absolutePath
    }

    override fun hasPendingResults(): Boolean = activeSession?.isCompleted == false

    override fun startListening() {
        synchronized(lifecycle) {
            if (released) return
            if (online == null && offline == null) {
                scope.launch(Dispatchers.Main) { if (!released) onError("语音识别模型未就绪") }
                return
            }
            if (hasPendingResults()) return

            recording = true
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    recordAndRecognize()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Log.e(TAG, "Recording or recognition failed", failure)
                    withContext(Dispatchers.Main) {
                        if (!released) onError(failure.message ?: "语音识别失败")
                    }
                }
            }
            activeSession = job
            job.invokeOnCompletion {
                scope.launch(Dispatchers.Main) {
                    if (!released && activeSession === job && !hasPendingResults()) onIdle()
                }
            }
            job.start()
        }
    }

    override fun stopListening() {
        recording = false
        stopRecorder()
    }

    override fun release() {
        val job = synchronized(lifecycle) {
            if (released) return
            released = true
            activeSession
        }
        stopListening()
        scope.launch {
            try {
                job?.join()
                online?.release()
                offline?.release()
            } finally {
                scope.cancel()
            }
        }
    }

    private fun stopRecorder() {
        try {
            audioRecord?.stop()
        } catch (_: IllegalStateException) {
            // An early Stop can arrive before AudioRecord starts.
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun recordAndRecognize(): Unit = coroutineScope {
        try {
            if (!recording) return@coroutineScope
            check(ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED) { "没有麦克风权限" }
            val minimum = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            check(minimum > 0) { "设备不支持 16kHz 单声道录音" }
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimum * 4, BLOCK_SAMPLES * 8)
            )
            audioRecord = recorder
            try {
                check(recorder.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
                if (!recording) return@coroutineScope
                recorder.startRecording()
                val audio = Channel<FloatArray>(QUEUE_BLOCKS)
                val reader = launch(Dispatchers.IO) { capture(recorder, audio) }
                try {
                    val recognizer = online
                    if (recognizer != null) {
                        recognizeOnline(recognizer, audio)
                    } else {
                        recognizeQwen(checkNotNull(offline), audio)
                    }
                } finally {
                    stopListening()
                    reader.join()
                    audio.cancel()
                }
            } finally {
                audioRecord = null
                recorder.release()
            }
        } finally {
            recording = false
        }
    }

    private fun capture(recorder: AudioRecord, audio: Channel<FloatArray>) {
        val buffer = ShortArray(BLOCK_SAMPLES)
        try {
            while (recording) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read < 0) {
                    if (!recording) break
                    throw IOException("麦克风读取失败（$read）")
                }
                if (read == 0) continue
                val samples = FloatArray(read) { buffer[it] / 32768f }
                if (audio.trySend(samples).isFailure) {
                    throw IOException("语音识别跟不上录音速度，请使用更轻量的模型")
                }
            }
            audio.close()
        } catch (failure: Exception) {
            audio.close(failure)
        }
    }

    private suspend fun recognizeOnline(recognizer: OnlineRecognizer, audio: Channel<FloatArray>) {
        val stream = recognizer.createStream()
        var lastPartial = ""
        try {
            if (language != "auto") stream.setOption("language", language)
            for (samples in audio) {
                if (released) return
                stream.acceptWaveform(samples, SAMPLE_RATE)
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                val text = recognizer.getResult(stream).text.trim()
                if (recognizer.isEndpoint(stream)) {
                    emitFinal(text, selectedLanguage())
                    lastPartial = ""
                    recognizer.reset(stream)
                    if (language != "auto") stream.setOption("language", language)
                } else if (text != lastPartial) {
                    lastPartial = text
                    withContext(Dispatchers.Main) { if (!released) onPartial(text) }
                }
            }
            if (!released) {
                // The upstream file decoder also supplies 800 ms of trailing silence.
                stream.acceptWaveform(FloatArray(SAMPLE_RATE * 8 / 10), SAMPLE_RATE)
                stream.inputFinished()
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                emitFinal(recognizer.getResult(stream).text.trim(), selectedLanguage())
            }
        } finally {
            stream.release()
        }
    }

    private suspend fun recognizeQwen(recognizer: OfflineRecognizer, audio: Channel<FloatArray>) {
        val utterance = FloatArray(QWEN_MAX_SAMPLES)
        var used = 0
        var voicedSamples = 0
        var trailingSilence = 0
        var previousBlock: FloatArray? = null

        suspend fun flush() {
            if (!released && voicedSamples >= QWEN_MIN_SPEECH) {
                val stream = recognizer.createStream()
                val result = try {
                    val hint = when (language) {
                        "ru" -> "Russian"
                        "zh" -> "Chinese"
                        "en" -> "English"
                        else -> ""
                    }
                    if (hint.isNotEmpty()) stream.setOption("language", hint)
                    stream.acceptWaveform(utterance.copyOf(used), SAMPLE_RATE)
                    recognizer.decode(stream)
                    recognizer.getResult(stream)
                } finally {
                    stream.release()
                }
                emitFinal(result.text.trim(), result.lang.ifBlank { selectedLanguage() })
            }
            used = 0
            voicedSamples = 0
            trailingSilence = 0
        }

        for (samples in audio) {
            if (released) return
            val voiced = samples.fold(0.0) { sum, sample -> sum + sample * sample } /
                samples.size > 0.0001
            if (used == 0) {
                if (!voiced) {
                    previousBlock = samples
                    continue
                }
                previousBlock?.let {
                    it.copyInto(utterance)
                    used = it.size
                }
                previousBlock = null
            }
            var from = 0
            while (from < samples.size) {
                val count = minOf(utterance.size - used, samples.size - from)
                samples.copyInto(utterance, used, from, from + count)
                from += count
                used += count
                if (voiced) {
                    voicedSamples += count
                    trailingSilence = 0
                } else {
                    trailingSilence += count
                }
                if (used == utterance.size || trailingSilence >= QWEN_END_SILENCE) flush()
            }
        }
        if (!released) flush()
    }

    private fun selectedLanguage(): String = language.takeUnless { it == "auto" }.orEmpty()

    private suspend fun emitFinal(text: String, detectedLanguage: String) = withContext(Dispatchers.Main) {
        if (!released) {
            onPartial("")
            if (text.isNotBlank()) onFinalResult(text, detectedLanguage)
        }
    }
}
