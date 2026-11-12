package com.bohanli.ruzhtranslator.asr

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.BuiltinNpuAcceleratorProvider
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import com.google.ai.edge.litert.TensorType
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import org.json.JSONObject

internal data class QwenNpuTranscript(val text: String, val language: String)

/** Runs the fixed five-second Qualcomm Qwen3-ASR LiteRT export. */
internal class QwenLiteRtRecognizer(
    context: Context,
    model: File,
    vocab: File,
    runtimeDirectory: File? = null
) : AutoCloseable {
    private val nativeResources = mutableListOf<AutoCloseable>()
    private val vocabulary = QwenByteVocabulary(vocab)
    private val compiledModel: CompiledModel
    private val encodeInputs: Map<String, TensorBuffer>
    private val encodeOutputs: Map<String, TensorBuffer>
    private val decodeInputs: Map<String, TensorBuffer>
    private val decodeOutputs: Map<String, TensorBuffer>
    private val features: TensorBuffer
    private val inputIds: TensorBuffer
    private val logits: TensorBuffer
    private val frameCount: Int
    private val tokenCount: Int
    private val logitsWidth: Int
    private var closed = false

    init {
        require(model.isFile) { "缺少千问 NPU 模型" }
        try {
            val environment = if (runtimeDirectory == null) {
                Environment.create(BuiltinNpuAcceleratorProvider(context))
            } else {
                Environment.create(
                    mapOf(Environment.Option.DispatchLibraryDir to runtimeDirectory.absolutePath)
                )
            }.also(nativeResources::add)
            val options = CompiledModel.Options(Accelerator.CPU, Accelerator.NPU).apply {
                qualcommOptions = CompiledModel.QualcommOptions(
                    htpPerformanceMode = CompiledModel.QualcommOptions.HtpPerformanceMode.DEFAULT
                )
            }
            compiledModel = CompiledModel.create(model.absolutePath, options, environment)
                .also(nativeResources::add)

            val featureShape = compiledModel.getInputTensorType(FIRST_INPUT, ENCODE).shape(TensorType.ElementType.FLOAT)
            val promptShape = compiledModel.getOutputTensorType(FIRST_OUTPUT, ENCODE).shape(TensorType.ElementType.FLOAT)
            val decoderPromptShape = compiledModel.getInputTensorType(FIRST_INPUT, DECODE).shape(TensorType.ElementType.FLOAT)
            val tokenShape = compiledModel.getInputTensorType(TOKEN_INPUT, DECODE).shape(TensorType.ElementType.INT)
            val maskShape = compiledModel.getInputTensorType(MASK_INPUT, DECODE).shape(TensorType.ElementType.FLOAT)
            val logitsShape = compiledModel.getOutputTensorType(FIRST_OUTPUT, DECODE).shape(TensorType.ElementType.FLOAT)
            require(featureShape == listOf(1, QwenAsrLogMel.MEL_BINS, 500)) { "千问 NPU 编码输入尺寸不匹配" }
            require(promptShape == listOf(1, 70, 1024) && promptShape == decoderPromptShape) {
                "千问 NPU 编码输出尺寸不匹配"
            }
            require(tokenShape == listOf(1, 64) && maskShape == listOf(1, 1, 134, 134) &&
                logitsShape == listOf(1, tokenShape[1], 151_936)) { "千问 NPU 解码尺寸不匹配" }
            frameCount = featureShape[2]
            tokenCount = tokenShape[1]
            logitsWidth = logitsShape[2]

            features = compiledModel.createInputBuffer(FIRST_INPUT, ENCODE).also(nativeResources::add)
            val prompt = compiledModel.createOutputBuffer(FIRST_OUTPUT, ENCODE).also(nativeResources::add)
            inputIds = compiledModel.createInputBuffer(TOKEN_INPUT, DECODE).also(nativeResources::add)
            val mask = compiledModel.createInputBuffer(MASK_INPUT, DECODE).also(nativeResources::add)
            logits = compiledModel.createOutputBuffer(FIRST_OUTPUT, DECODE).also(nativeResources::add)
            encodeInputs = mapOf(FIRST_INPUT to features)
            encodeOutputs = mapOf(FIRST_OUTPUT to prompt)
            decodeInputs = mapOf(FIRST_INPUT to prompt, TOKEN_INPUT to inputIds, MASK_INPUT to mask)
            decodeOutputs = mapOf(FIRST_OUTPUT to logits)

            val maskWidth = maskShape.last()
            val causalMask = FloatArray(maskWidth * maskWidth) { -0.7f * Float.MAX_VALUE }
            for (row in 0 until maskWidth) {
                causalMask.fill(0f, row * maskWidth, row * maskWidth + row + 1)
            }
            mask.writeFloat(causalMask)

            // Both signatures contain an AOT Qualcomm dispatch op. Execute them before readiness.
            features.writeFloat(QwenAsrLogMel.compute(floatArrayOf(), frameCount))
            compiledModel.run(encodeInputs, encodeOutputs, ENCODE)
            inputIds.writeInt(IntArray(tokenCount).also { it[0] = START_TOKEN })
            compiledModel.run(decodeInputs, decodeOutputs, DECODE)
            bestToken(0)
        } catch (failure: Throwable) {
            runCatching(::close).exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    @Synchronized
    fun transcribe(samples: FloatArray, language: String): QwenNpuTranscript {
        check(!closed) { "千问 NPU 引擎已关闭" }
        require(samples.size <= MAX_SAMPLES) { "千问 NPU 每次最多识别五秒音频" }
        val languageToken = when (language) {
            "auto" -> null
            "ru" -> RUSSIAN_TOKEN
            "zh" -> CHINESE_TOKEN
            "en" -> ENGLISH_TOKEN
            else -> throw IllegalArgumentException("不支持的语音输入语种")
        }
        if (samples.isEmpty()) return QwenNpuTranscript("", "")

        features.writeFloat(QwenAsrLogMel.compute(samples, frameCount))
        compiledModel.run(encodeInputs, encodeOutputs, ENCODE)

        val tokens = IntArray(tokenCount)
        tokens[0] = START_TOKEN
        var used = 1
        if (languageToken != null) {
            tokens[used++] = LANGUAGE_TOKEN
            tokens[used++] = languageToken
            tokens[used++] = ASR_TEXT_TOKEN
        }
        // The graph scores every position. The last populated position predicts the next token.
        while (used < tokenCount) {
            inputIds.writeInt(tokens)
            compiledModel.run(decodeInputs, decodeOutputs, DECODE)
            val token = bestToken(used - 1)
            if (token == END_TOKEN || token == END_OF_TEXT_TOKEN) break
            tokens[used++] = token
        }

        val marker = (1 until used).firstOrNull { tokens[it] == ASR_TEXT_TOKEN }
        if (marker == null) return QwenNpuTranscript(vocabulary.decode(tokens, 1, used), "")
        val text = vocabulary.decode(tokens, marker + 1, used)
        if (languageToken != null) return QwenNpuTranscript(text, language)

        val metadata = vocabulary.decode(tokens, 1, marker).lowercase(Locale.ROOT)
        val detected = metadata.lineSequence().map(String::trim)
            .firstOrNull { it.startsWith("language ") }?.removePrefix("language ")
        val detectedCode = when (detected) {
            "russian" -> "ru"
            "chinese", "cantonese" -> "zh"
            "english" -> "en"
            else -> ""
        }
        return QwenNpuTranscript(text, detectedCode)
    }

    private fun bestToken(row: Int): Int {
        val scores = logits.readFloat()
        val start = row * logitsWidth
        val end = start + logitsWidth
        check(scores.size >= end) { "千问 NPU 解码输出不完整" }
        var bestIndex = start
        for (index in start + 1 until end) {
            if (scores[index] > scores[bestIndex]) bestIndex = index
        }
        check(scores[bestIndex].isFinite()) { "千问 NPU 解码输出无效" }
        return bestIndex - start
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        var firstFailure: Throwable? = null
        for (resource in nativeResources.asReversed()) {
            try {
                resource.close()
            } catch (failure: Throwable) {
                val previous = firstFailure
                if (previous == null) firstFailure = failure else previous.addSuppressed(failure)
            }
        }
        nativeResources.clear()
        firstFailure?.let { throw it }
    }

    private fun TensorType.shape(expectedType: TensorType.ElementType): List<Int> {
        require(elementType == expectedType) { "千问 NPU 张量类型不匹配" }
        return requireNotNull(layout) { "千问 NPU 张量缺少布局" }.dimensions
    }

    companion object {
        const val MAX_SAMPLES = 80_000
        private const val ENCODE = "encode"
        private const val DECODE = "decode"
        private const val FIRST_INPUT = "args_0"
        private const val TOKEN_INPUT = "args_1"
        private const val MASK_INPUT = "args_2"
        private const val FIRST_OUTPUT = "output_0"

        // IDs from the Qwen3-ASR vocabulary and its added_tokens_decoder.
        private const val START_TOKEN = 198 // Newline after the assistant prompt.
        private const val LANGUAGE_TOKEN = 11_528
        private const val CHINESE_TOKEN = 8_453
        private const val ENGLISH_TOKEN = 6_364
        private const val RUSSIAN_TOKEN = 8_522
        private const val END_OF_TEXT_TOKEN = 151_643
        private const val END_TOKEN = 151_645
        private const val ASR_TEXT_TOKEN = 151_704
    }
}

/** Qwen encodes vocabulary pieces with the reversible GPT-2 byte-to-Unicode alphabet. */
private class QwenByteVocabulary(file: File) {
    private val pieces = arrayOfNulls<String>(151_643)
    private val byteForCharacter = IntArray(512) { -1 }

    init {
        require(file.isFile) { "缺少千问词表" }
        val vocab = JSONObject(file.readText())
        require(vocab.length() == pieces.size) { "千问词表尺寸不匹配" }
        for (piece in vocab.keys()) {
            val id = vocab.getInt(piece)
            require(id in pieces.indices && pieces[id] == null) { "千问词表令牌无效" }
            pieces[id] = piece
        }
        var nextCharacter = 256
        for (byte in 0..255) {
            val character = if (byte in 33..126 || byte in 161..172 || byte in 174..255) {
                byte
            } else {
                nextCharacter++
            }
            byteForCharacter[character] = byte
        }
    }

    fun decode(tokens: IntArray, from: Int, until: Int): String {
        val bytes = ByteArrayOutputStream()
        for (index in from until until) {
            val piece = pieces.getOrNull(tokens[index]) ?: continue
            for (character in piece) {
                val byte = byteForCharacter.getOrElse(character.code) { -1 }
                require(byte >= 0) { "千问词表字符无效" }
                bytes.write(byte)
            }
        }
        return bytes.toString(Charsets.UTF_8.name()).trim()
    }
}
