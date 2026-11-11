package com.bohanli.ruzhtranslator.asr

import java.util.Locale

internal enum class AsrBackend { NPU, CPU }

internal object AsrAccelerationPolicy {
    fun chipset(androidApi: Int, socModel: String?): String? {
        if (androidApi < 31) return null
        return socModel?.trim()?.uppercase(Locale.ROOT)?.substringBefore('-')
            ?.takeIf { it == "SM8750" || it == "SM8850" }
    }

    fun backend(
        modelId: String,
        chipset: String?,
        npuBuild: Boolean,
        npuReady: Boolean,
        cpuReady: Boolean
    ): AsrBackend? = when {
        npuBuild && npuReady && chipset(31, chipset) != null &&
            (modelId == MultilingualAsrCatalog.QWEN || modelId == MultilingualAsrCatalog.NEMOTRON) ->
            AsrBackend.NPU
        cpuReady -> AsrBackend.CPU
        else -> null
    }
}
