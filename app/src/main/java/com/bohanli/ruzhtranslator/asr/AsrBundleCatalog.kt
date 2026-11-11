package com.bohanli.ruzhtranslator.asr

import com.bohanli.ruzhtranslator.settings.AppSettings

/**
 * Exact Android-compatible INT8 ONNX bundles. The original ModelScope
 * safetensors/NeMo checkpoints are NOT interchangeable with these ONNX files.
 *
 * Always select a byte-compatible domestic ModelScope repository first IF one
 * has been verified; otherwise select China's HF Mirror. Never auto-fallback
 * to direct huggingface.co or a GitHub proxy.
 */
object AsrBundleCatalog {
    data class Component(val path: String, val bytes: Long)
    data class Bundle(
        val id: String,
        val title: String,
        val nativeModelScope: String,
        val convertedHfRepository: String,
        val files: List<Component>,
        val streaming: Boolean,
        val modelScopeCompatibleRepository: String? = null,
        val modelScopeCompatibleRevision: String? = null
    ) {
        val totalBytes: Long get() = files.sumOf { it.bytes }
        fun sourceUrls(path: String, preferred: String, customBase: String = ""): List<String> {
            require(files.any { it.path == path }) { "未经记录的模型文件：$path" }
            val urls = mutableListOf<String>()
            // No exact matching INT8 ONNX digest has yet been verified on ModelScope.
            // Original checkpoint URLs are shown as research references only.
            val ms = modelScopeCompatibleRepository
            if (ms != null) {
                val revision = modelScopeCompatibleRevision ?: "master"
                urls.add("https://modelscope.cn/models/$ms/resolve/$revision/$path")
            }
            val mirror = "https://hf-mirror.com/$convertedHfRepository/resolve/main/$path"
            if (preferred == AppSettings.SOURCE_CUSTOM) {
                require(customBase.startsWith("https://", true)) { "只允许 HTTPS 国内镜像下载地址" }
                urls.add(customBase.trimEnd('/') + "/" + path)
            }
            urls.add(mirror)
            // No foreign direct URL in the user's requested automatic policy.
            return urls.distinct()
        }
    }

    val QWEN = Bundle(
        id = AppSettings.ASR_MODEL_QWEN3,
        title = "Qwen3-ASR-0.6B INT8 · 中文/英文/俄文",
        nativeModelScope = "Qwen/Qwen3-ASR-0.6B",
        convertedHfRepository = "csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25",
        files = listOf(
            Component("conv_frontend.onnx", 44_148_281),
            Component("encoder.int8.onnx", 182_491_662),
            Component("decoder.int8.onnx", 755_914_231),
            Component("tokenizer/merges.txt", 1_671_853),
            Component("tokenizer/vocab.json", 2_776_833),
            Component("tokenizer/tokenizer_config.json", 12_487)
        ),
        streaming = false
    )
    val NEMOTRON = Bundle(
        id = AppSettings.ASR_MODEL_NEMOTRON35,
        title = "Nemotron 3.5 ASR Streaming 0.6B INT8 · 560ms",
        nativeModelScope = "nv-community/nemotron-3.5-asr-streaming-0.6b",
        convertedHfRepository = "csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11",
        files = listOf(
            Component("encoder.int8.onnx", 657_601_403),
            Component("decoder.int8.onnx", 14_978_075),
            Component("joiner.int8.onnx", 9_504_438),
            Component("tokens.txt", 131_440)
        ),
        streaming = true
    )
    val all get() = listOf(QWEN, NEMOTRON)
    fun forId(id: String) = all.firstOrNull { it.id == id }
        ?: throw IllegalArgumentException("不支持的多语言 ASR 引擎：$id")
}
