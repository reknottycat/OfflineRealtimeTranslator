package com.bohanli.ruzhtranslator.core

import com.bohanli.ruzhtranslator.settings.AppSettings

/**
 * Pinned, reproducible model catalog. Android CPU/GPU bundle, NOT a web/NPU build.
 * SHA-256 and exact length protect against HTML error pages, partial downloads,
 * unofficial mirrors, malicious files, and upstream "main" updates.
 */
object ModelCatalog {
    const val GEMMA4_ID = "gemma-4-E2B-it-litert-lm"
    const val GEMMA4_FILE = "gemma-4-E2B-it.litertlm"
    const val GEMMA4_BYTES = 2_588_147_712L
    const val GEMMA4_SHA256 =
        "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
    const val HF_REVISION = "9262660a1676eed6d0c477ab1a86344430854664"
    private const val MODEL_PATH =
        "/litert-community/gemma-4-E2B-it-litert-lm/resolve/" +
        HF_REVISION + "/" + GEMMA4_FILE

    fun sourceUrl(source: String): String? = when (source) {
        AppSettings.SOURCE_HF -> "https://huggingface.co$MODEL_PATH"
        AppSettings.SOURCE_HF_MIRROR -> "https://hf-mirror.com$MODEL_PATH"
        // An official/verified ModelScope mirror was not found. Accept the
        // user-supplied exact HTTPS file URL and verify it against the pinned hash.
        AppSettings.SOURCE_MODELSCOPE -> null
        else -> null
    }
}
