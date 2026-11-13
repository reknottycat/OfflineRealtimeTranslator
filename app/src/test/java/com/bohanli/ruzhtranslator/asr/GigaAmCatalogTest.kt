package com.bohanli.ruzhtranslator.asr

import com.bohanli.ruzhtranslator.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GigaAmCatalogTest {
    @Test fun modelIdentityIsPinnedToExactOfficialRelease() {
        assertEquals(236_457_977L, GigaAmCatalog.MODEL_BYTES)
        assertEquals("d0ce4aef25f58d495781ee8f05320d9e51b821f47804e07aa6549b53a72f67e8",
            GigaAmCatalog.MODEL_SHA256)
        assertEquals(40, GigaAmCatalog.REVISION.length)
        assertEquals(64, GigaAmCatalog.MODEL_SHA256.length)
        assertEquals(GigaAmCatalog.MODEL_BYTES, GigaAmCatalog.SPEC.bytes)
        assertTrue(GigaAmCatalog.SPEC.acceptsSize(236_457_977L))
    }

    @Test fun domesticMirrorIsFirstAndOfficialSourceIsFallback() {
        val urls = GigaAmCatalog.urls(VoskModelCatalog.SOURCE_AUTO)
        assertEquals(listOf(GigaAmCatalog.SPEC.url("hf-mirror.com"),
            GigaAmCatalog.SPEC.url("huggingface.co")), urls)
        assertTrue(urls.all { it.endsWith("/model.int8.onnx") })
    }

    @Test fun originalSourceGetsPriorityOnExplicitSelection() {
        assertTrue(GigaAmCatalog.urls(VoskModelCatalog.SOURCE_OFFICIAL)[0]
            .startsWith("https://huggingface.co/"))
    }

    @Test fun customMirrorKeepsPinnedOfficialFallback() {
        val urls = GigaAmCatalog.urls(VoskModelCatalog.SOURCE_CUSTOM,
            "https://trusted.example/gigaam.onnx")
        assertEquals("https://trusted.example/gigaam.onnx", urls.first())
        assertEquals(3, urls.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectUnsafeCustomSource() {
        GigaAmCatalog.urls(VoskModelCatalog.SOURCE_CUSTOM, "http://example.org/gigaam.onnx")
    }

    @Test fun allThreeAsrChoicesRemainDistinctFromTranslationEngines() {
        assertEquals("vosk-model-small-ru-0.22", AppSettings.ASR_MODEL_SMALL)
        assertEquals("vosk-model-ru-0.42", AppSettings.ASR_MODEL_LARGE)
        assertEquals("sherpa-gigaam-v2-ru", AppSettings.ASR_MODEL_GIGAAM)
        assertTrue(GigaAmCatalog.MODEL_ID != AppSettings.ASR_MODEL_SMALL)
        assertTrue(GigaAmCatalog.MODEL_ID != AppSettings.ASR_MODEL_LARGE)
        assertTrue(GigaAmCatalog.MODEL_ID != AppSettings.ENGINE_LMT)
    }
}
