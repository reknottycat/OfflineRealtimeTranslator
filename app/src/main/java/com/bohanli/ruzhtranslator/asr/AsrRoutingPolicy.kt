package com.bohanli.ruzhtranslator.asr

import com.bohanli.ruzhtranslator.settings.AppSettings

/** Pure routing rules: ASR language is not the same thing as translation direction. */
object AsrRoutingPolicy {
    private val multilingual = setOf(AppSettings.ASR_MODEL_QWEN3, AppSettings.ASR_MODEL_NEMOTRON35)

    fun supportsLanguage(model: String, selected: String): Boolean =
        model in multilingual || selected == AppSettings.ASR_LANG_RU

    /** Detect a script when a streaming runtime does not expose a trustworthy language ID. */
    fun resolvedLanguage(selected: String, reported: String?, text: String): String {
        if (selected != AppSettings.ASR_LANG_AUTO) return selected
        val id = reported.orEmpty().lowercase()
        when {
            id.startsWith("zh") || id == "chinese" -> return AppSettings.ASR_LANG_ZH
            id.startsWith("ru") || id == "russian" -> return AppSettings.ASR_LANG_RU
            id.startsWith("en") || id == "english" -> return AppSettings.ASR_LANG_EN
        }
        if (text.any { it in '\u4e00'..'\u9fff' }) return AppSettings.ASR_LANG_ZH
        if (text.any { it in '\u0400'..'\u052f' }) return AppSettings.ASR_LANG_RU
        // Latin-only text can be transcribed in many languages; do not guess English.
        return "unknown"
    }

    /** The existing translation checkpoint is Russian -> Chinese only. */
    fun mayTranslateIntoChinese(language: String): Boolean = language == AppSettings.ASR_LANG_RU
    fun badge(language: String): String = when (language) {
        AppSettings.ASR_LANG_RU -> "俄语"
        AppSettings.ASR_LANG_ZH -> "中文"
        AppSettings.ASR_LANG_EN -> "English"
        else -> "未知语言"
    }
}

/** Never report NPU/GPU acceleration just because a user selected its name. */
object AsrComputePolicy {
    data class Decision(val provider: String, val description: String, val verifiedAccelerator: Boolean)
    fun select(requested: String): Decision = when (requested) {
        AppSettings.ASR_COMPUTE_AUTO, AppSettings.ASR_COMPUTE_CPU ->
            Decision("cpu", "CPU 受控线程（2 线程）；QNN/NeuroPilot 尚未验收", false)
        AppSettings.ASR_COMPUTE_GPU ->
            Decision("unsupported", "当前 sherpa-onnx AAR 未配置经核验的手机 GPU Execution Provider", false)
        AppSettings.ASR_COMPUTE_NPU ->
            Decision("unsupported", "需针对骁龙 QNN 或天玑 NeuroPilot 编译委托，尚未验证，不能冒充 NPU 加速", false)
        else -> Decision("unsupported", "未知硬件执行模式", false)
    }
}
