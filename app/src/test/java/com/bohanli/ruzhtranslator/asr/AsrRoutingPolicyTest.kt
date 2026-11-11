package com.bohanli.ruzhtranslator.asr

import com.bohanli.ruzhtranslator.settings.AppSettings
import org.junit.Assert.*
import org.junit.Test

class AsrRoutingPolicyTest {
    @Test fun russianOnlyModelsCannotMasqueradeAsAutomaticLanguageDetection() {
        for (model in listOf(AppSettings.ASR_MODEL_SMALL, AppSettings.ASR_MODEL_LARGE, AppSettings.ASR_MODEL_GIGAAM)) {
            assertFalse(AsrRoutingPolicy.supportsLanguage(model, AppSettings.ASR_LANG_AUTO))
            assertFalse(AsrRoutingPolicy.supportsLanguage(model, AppSettings.ASR_LANG_ZH))
            assertTrue(AsrRoutingPolicy.supportsLanguage(model, AppSettings.ASR_LANG_RU))
        }
    }
    @Test fun multilingualModelsAllowManualOrAuto() {
        for (model in listOf(AppSettings.ASR_MODEL_QWEN3, AppSettings.ASR_MODEL_NEMOTRON35)) {
            for (lang in listOf("ru", "zh", "en", "auto")) assertTrue(AsrRoutingPolicy.supportsLanguage(model, lang))
        }
    }
    @Test fun chineseTextMustNeverBeFedToRussianTranslator() {
        val lang = AsrRoutingPolicy.resolvedLanguage("auto", "zh-CN", "你好，我们正在测试")
        assertEquals("zh", lang)
        assertFalse(AsrRoutingPolicy.mayTranslateIntoChinese(lang))
    }
    @Test fun englishIsNotRussian() {
        assertEquals("en", AsrRoutingPolicy.resolvedLanguage("auto", "en-US", "Hello there"))
        assertFalse(AsrRoutingPolicy.mayTranslateIntoChinese("en"))
    }
    @Test fun cyrillicIsRussianOnlyIfLanguageNotReported() {
        assertEquals("ru", AsrRoutingPolicy.resolvedLanguage("auto", null, "добрый день"))
    }
    @Test fun latinWithoutReportedIdIsUnknownNotForcedRussianOrEnglish() {
        assertEquals("unknown", AsrRoutingPolicy.resolvedLanguage("auto", null, "Bonjour"))
        assertFalse(AsrRoutingPolicy.mayTranslateIntoChinese("unknown"))
    }
    @Test fun manualChineseForcesChineseRegardlessOfScript() {
        assertEquals("zh", AsrRoutingPolicy.resolvedLanguage("zh", "ru-RU", "Привет"))
    }
    @Test fun unverifiedNpuCannotBeAdvertisedAsRunning() {
        assertFalse(AsrComputePolicy.select("npu").verifiedAccelerator)
        assertEquals("unsupported", AsrComputePolicy.select("npu").provider)
        assertEquals("cpu", AsrComputePolicy.select("auto").provider)
    }
}
