package com.bohanli.ruzhtranslator.core

import com.bohanli.ruzhtranslator.settings.AppSettings

/** Immutable, per-engine artifacts. A mirror is trusted only after SHA-256 verification. */
object ModelCatalog {
    data class Spec(
        val engine: String,
        val title: String,
        val directory: String,
        val fileName: String,
        val bytes: Long,
        val maximumBytes: Long,
        val sha256: String,
        val repository: String,
        val revision: String,
        val sizeLabel: String
    ) {
        init {
            require(Regex("[a-f0-9]{64}").matches(sha256))
            require(Regex("[a-f0-9]{40}").matches(revision))
            require(!fileName.contains('/') && !directory.contains('/'))
        }
        fun acceptsSize(size: Long): Boolean = size > 0L && size <= maximumBytes && (bytes == 0L || size == bytes)
        fun url(host: String): String = "https://$host/$repository/resolve/$revision/$fileName"
    }
    const val GEMMA4_ID = "gemma-4-E2B-it-litert-lm"
    const val GEMMA4_FILE = "gemma-4-E2B-it.litertlm"
    const val GEMMA4_BYTES = 2_588_147_712L
    const val GEMMA4_SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
    const val HF_REVISION = "9262660a1676eed6d0c477ab1a86344430854664"
    val GEMMA4 = Spec(AppSettings.ENGINE_GEMMA4, "Gemma 4 E2B", GEMMA4_ID, GEMMA4_FILE,
        GEMMA4_BYTES, GEMMA4_BYTES, GEMMA4_SHA256, "litert-community/gemma-4-E2B-it-litert-lm", HF_REVISION, "2.59 GB")
    val LMT = Spec(AppSettings.ENGINE_LMT, "NiuTrans LMT-60-1.7B Q4_K_M", "lmt-60-1.7b-q4_k_m",
        "LMT-60-1.7B.Q4_K_M.gguf", 0L, 1_600_000_000L,
        "54c6244f100330264cef607188413ee82cb8a254f0cf950011aaa9cee74f7eb5",
        "mradermacher/LMT-60-1.7B-GGUF", "f03d51b43463d8a457eee1cd765220de69e9bac8", "约 1.28 GB")
    // LMT's full digest binds both its exact bytes and contents; the upstream web
    // view only displayed a rounded size, so do not invent an exact byte count.
    fun forEngine(engine: String): Spec = when (engine) {
        AppSettings.ENGINE_GEMMA4 -> GEMMA4
        AppSettings.ENGINE_LMT -> LMT
        else -> throw IllegalArgumentException("Unknown translation engine: $engine")
    }
    fun sourceUrl(source: String, engine: String = AppSettings.ENGINE_GEMMA4): String? {
        val model = forEngine(engine)
        return when (source) {
            AppSettings.SOURCE_HF -> model.url("huggingface.co")
            AppSettings.SOURCE_HF_MIRROR -> model.url("hf-mirror.com")
            AppSettings.SOURCE_MODELSCOPE -> null
            else -> null
        }
    }
}
