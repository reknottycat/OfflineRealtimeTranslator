package com.bohanli.ruzhtranslator.asr

import com.bohanli.ruzhtranslator.settings.AppSettings

/**
 * Russian Vosk archives verified against alphacephei's model index.
 * ModelScope does not currently have a verified matching ZIP for either model:
 * never fabricate a ModelScope URL or silently install a different ASR model.
 */
object VoskModelCatalog {
    const val SOURCE_AUTO = "auto"
    const val SOURCE_DOMESTIC = "domestic"
    const val SOURCE_OFFICIAL = "official"
    const val SOURCE_CUSTOM = "custom"

    data class Spec(
        val id: String,
        val title: String,
        val archiveBytes: Long,
        val archiveSha256: String?,
        val maxExpandedBytes: Long,
        val estimatedExpandedBytes: Long
    ) {
        val archiveName get() = "$id.zip"
        val officialUrl get() = "https://alphacephei.com/vosk/models/$archiveName"
        val isLarge get() = id == AppSettings.ASR_MODEL_LARGE
    }

    val SMALL = Spec(
        AppSettings.ASR_MODEL_SMALL, "俄语 Small 0.22",
        46_236_750L, "961d5ff98a17f4aa6de69864d0aa71fa5bac682301d2b5d17a3f24c5c99a46d4",
        512L * 1024 * 1024, 180L * 1024 * 1024
    )
    val LARGE = Spec(
        AppSettings.ASR_MODEL_LARGE, "俄语 Large 0.42",
        1_837_429_010L, null, 8L * 1024 * 1024 * 1024, 4L * 1024 * 1024 * 1024
    )
    fun model(id: String): Spec = when (id) {
        SMALL.id -> SMALL
        LARGE.id -> LARGE
        else -> throw IllegalArgumentException("未知 Vosk 俄语模型：$id")
    }
    // Exact pinned HF repository and revision. Mirror is an access path, not a separate model.
    private const val SMALL_HF_PATH =
        "rhasspy/vosk-models/resolve/e7ac2109d134b5f2404ba95389b2fb51916d4cab/ru/vosk-model-small-ru-0.22.zip"
    private val smallDomestic get() = listOf(
        "https://hf-mirror.com/$SMALL_HF_PATH",
        "https://huggingface.co/$SMALL_HF_PATH"
    )
    /** A small-model GitHub release has an independently published matching SHA-256. */
    private val smallGithub get() =
        "https://github.com/BartekReterski/VoskModels/releases/download/v1/vosk-model-small-ru-0.22.zip"

    fun urls(spec: Spec, source: String, customUrl: String = ""): List<String> {
        val fallback = if (spec.isLarge) listOf(spec.officialUrl) else
            smallDomestic + listOf(spec.officialUrl, smallGithub)
        val priority = when (source) {
            SOURCE_AUTO, SOURCE_DOMESTIC -> emptyList()
            SOURCE_OFFICIAL -> listOf(spec.officialUrl)
            SOURCE_CUSTOM -> {
                require(customUrl.trim().startsWith("https://", true)) { "请填写 HTTPS 模型 ZIP 直链" }
                listOf(customUrl.trim())
            }
            else -> throw IllegalArgumentException("未知 Vosk 下载来源")
        }
        return (priority + fallback).distinct()
    }
}
