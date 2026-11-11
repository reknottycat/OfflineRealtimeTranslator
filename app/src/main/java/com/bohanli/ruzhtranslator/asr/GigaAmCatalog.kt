package com.bohanli.ruzhtranslator.asr

import com.bohanli.ruzhtranslator.core.ModelCatalog
import com.bohanli.ruzhtranslator.settings.AppSettings

/**
 * GigaAM v2 Russian CTC INT8 converted by sherpa-onnx, not a Vosk model.
 * Official file pinned to the immutable Hugging Face commit and LFS SHA-256.
 * No matching ModelScope mirror has been authenticated.
 */
object GigaAmCatalog {
    const val MODEL_ID = AppSettings.ASR_MODEL_GIGAAM
    const val MODEL_FILE = "model.int8.onnx"
    const val REPO = "csukuangfj/sherpa-onnx-nemo-ctc-giga-am-v2-russian-2025-04-19"
    const val REVISION = "499bb8aa0334ec8399ff827f00d791dc0ac7015d"
    const val MODEL_BYTES = 236_457_977L
    const val MODEL_SHA256 = "d0ce4aef25f58d495781ee8f05320d9e51b821f47804e07aa6549b53a72f67e8"
    const val TOKENS_FILE = "tokens.txt"
    const val TOKENS_ASSET = "gigaam-v2-russian-tokens.txt"
    const val SHERPA_VERSION = "2.0.1"

    val SPEC = ModelCatalog.Spec(MODEL_ID, "GigaAM v2 俄语 CTC INT8 · sherpa-onnx",
        MODEL_ID, MODEL_FILE, MODEL_BYTES, MODEL_BYTES, MODEL_SHA256,
        REPO, REVISION, "236 MB · Android CPU")

    fun urls(source: String, custom: String = ""): List<String> {
        val mirror = SPEC.url("hf-mirror.com")
        val official = SPEC.url("huggingface.co")
        val preferred = when (source) {
            VoskModelCatalog.SOURCE_AUTO, VoskModelCatalog.SOURCE_DOMESTIC, AppSettings.SOURCE_MODELSCOPE -> listOf(mirror)
            VoskModelCatalog.SOURCE_OFFICIAL -> listOf(official)
            VoskModelCatalog.SOURCE_CUSTOM -> {
                require(custom.trim().startsWith("https://", true)) { "仅支持 HTTPS ONNX 模型直链" }
                listOf(custom.trim())
            }
            else -> throw IllegalArgumentException("未知 GigaAM 下载来源")
        }
        return (preferred + listOf(mirror, official)).distinct()
    }
}
