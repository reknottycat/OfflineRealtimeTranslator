package com.bohanli.ruzhtranslator.core

import com.bohanli.ruzhtranslator.settings.AppSettings

/** Immutable per-engine artifacts; source failover never bypasses SHA-256 verification. */
object ModelCatalog {
    data class Spec(
        val engine: String, val title: String, val directory: String, val fileName: String,
        val bytes: Long, val maximumBytes: Long, val sha256: String,
        val repository: String, val revision: String, val sizeLabel: String,
        val modelScopeRepository: String? = null, val modelScopeRevision: String? = null
    ) {
        init {
            require(Regex("[a-f0-9]{64}").matches(sha256))
            require(Regex("[a-f0-9]{40}").matches(revision))
            require((modelScopeRepository == null) == (modelScopeRevision == null))
            require(modelScopeRevision == null || Regex("[a-f0-9]{40}").matches(modelScopeRevision))
            require(!fileName.contains('/') && !directory.contains('/'))
        }
        fun acceptsSize(size: Long): Boolean = size > 0L && size <= maximumBytes && (bytes == 0L || size == bytes)
        fun url(host: String): String = "https://$host/$repository/resolve/$revision/$fileName"
        fun modelScopeUrl(api: Boolean = false): String? {
            val repo = modelScopeRepository ?: return null
            val ref = modelScopeRevision ?: return null
            return if (api) "https://modelscope.cn/api/v1/models/$repo/repo?Revision=$ref&FilePath=$fileName"
            else "https://modelscope.cn/models/$repo/resolve/$ref/$fileName"
        }
    }
    const val GEMMA4_ID = "gemma-4-E2B-it-litert-lm"
    const val GEMMA4_FILE = "gemma-4-E2B-it.litertlm"
    const val GEMMA4_BYTES = 2_588_147_712L
    const val GEMMA4_SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
    const val HF_REVISION = "9262660a1676eed6d0c477ab1a86344430854664"
    val GEMMA4 = Spec(AppSettings.ENGINE_GEMMA4, "Gemma 4 E2B", GEMMA4_ID, GEMMA4_FILE,
        GEMMA4_BYTES, GEMMA4_BYTES, GEMMA4_SHA256, "litert-community/gemma-4-E2B-it-litert-lm", HF_REVISION, "2.59 GB",
        "litert-community/gemma-4-E2B-it-litert-lm", "1bacc155f57965ae45832a9ccfe96cdfcb0e94e9")
    val LMT = Spec(AppSettings.ENGINE_LMT, "NiuTrans LMT-60-1.7B Q4_K_M", "lmt-60-1.7b-q4_k_m",
        "LMT-60-1.7B.Q4_K_M.gguf", 1_282_439_264L, 1_282_439_264L,
        "54c6244f100330264cef607188413ee82cb8a254f0cf950011aaa9cee74f7eb5",
        "mradermacher/LMT-60-1.7B-GGUF", "f03d51b43463d8a457eee1cd765220de69e9bac8", "约 1.28 GB")
    // Tencent's own Q4_K_M release, not a third-party conversion or STQ kernel build.
    // Exact size and digest are published in the upstream Git LFS pointer.
    val HYMT2 = Spec(AppSettings.ENGINE_HYMT2, "腾讯 HY-MT2-1.8B Q4_K_M", "hy-mt2-1.8b-q4_k_m",
        "Hy-MT2-1.8B-Q4_K_M.gguf", 1_133_080_448L, 1_133_080_448L,
        "dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699",
        "tencent/Hy-MT2-1.8B-GGUF", "a0c709d9fac510f2c807aa3af52872340dc37a4a", "1.13 GB",
        "Tencent-Hunyuan/Hy-MT2-1.8B-GGUF", "ef1d40b8b315575d30d1eb6579996130b8fc0bb2")
    val all: List<Spec> get() = listOf(GEMMA4, LMT, HYMT2)
    fun forEngine(engine: String): Spec = when (engine) {
        AppSettings.ENGINE_GEMMA4 -> GEMMA4
        AppSettings.ENGINE_LMT -> LMT
        AppSettings.ENGINE_HYMT2 -> HYMT2
        else -> throw IllegalArgumentException("Unknown translation engine: $engine")
    }
    /** ABI contract with lmt::Kind. Gemma must never be passed to the GGUF bridge. */
    fun nativeKind(engine: String): Int = when (engine) {
        AppSettings.ENGINE_LMT -> 0
        AppSettings.ENGINE_HYMT2 -> 1
        else -> throw IllegalArgumentException("Not a GGUF translation engine: $engine")
    }
    fun sourceUrl(source: String, engine: String = AppSettings.ENGINE_GEMMA4): String? {
        val model = forEngine(engine)
        return when (source) {
            AppSettings.SOURCE_HF -> model.url("huggingface.co")
            AppSettings.SOURCE_HF_MIRROR -> model.url("hf-mirror.com")
            AppSettings.SOURCE_MODELSCOPE -> model.modelScopeUrl()
            else -> null
        }
    }

    /** User-requested proxy candidates, not a claim of reachability on every network.
     * Each must return the exact byte range/length, and the final artifact must match SHA-256.
     * gh-proxy.org currently redirects to gh-proxy.com; they are not independent operators.
     */
    val proxyPrefixes: List<String> = listOf(
        "https://gh-proxy.org/", "https://ghproxy.imciel.com/", "https://gh-proxy.com/"
    )

    /** Preferred source first, with all remaining verified/candidate routes as bounded fallbacks.
     * ModelScope resolve and API are two routes to the same platform, not two independent mirrors.
     * No ModelScope LMT URL is fabricated when the exact GGUF has not been located there.
     */
    fun downloadUrls(source: String, engine: String, customUrl: String = ""): List<String> {
        val spec = forEngine(engine)
        val modelScope = listOfNotNull(spec.modelScopeUrl(), spec.modelScopeUrl(api = true))
        val mirror = spec.url("hf-mirror.com")
        val official = spec.url("huggingface.co")
        val proxies = proxyPrefixes.map { it + official }
        val automatic = modelScope + mirror + proxies + official
        val preferred = when (source) {
            AppSettings.SOURCE_AUTO -> emptyList()
            AppSettings.SOURCE_MODELSCOPE -> modelScope
            AppSettings.SOURCE_CN_PROXY -> proxies
            AppSettings.SOURCE_HF_MIRROR -> listOf(mirror)
            AppSettings.SOURCE_HF -> listOf(official)
            AppSettings.SOURCE_CUSTOM -> listOf(customUrl.trim()).also {
                require(it.single().isNotBlank()) { "请填写对应模型的 HTTPS 文件直链" }
                DownloadProtocol.requireHttps(it.single())
            }
            else -> throw IllegalArgumentException("未知下载来源，请重新选择自动模式")
        }
        return (preferred + automatic).distinct()
    }
}
