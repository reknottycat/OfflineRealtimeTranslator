package com.bohanli.ruzhtranslator.asr

import com.bohanli.ruzhtranslator.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoskModelCatalogTest {
    @Test fun smallModelIdSizeAndShaArePinned() {
        val spec = VoskModelCatalog.SMALL
        assertEquals(AppSettings.ASR_MODEL_SMALL, spec.id)
        assertEquals(46_236_750L, spec.archiveBytes)
        assertEquals(64, spec.archiveSha256!!.length)
    }
    @Test fun largeUsesOfficialSizeAndDisclosesNoKnownHash() {
        val spec = VoskModelCatalog.LARGE
        assertEquals(AppSettings.ASR_MODEL_LARGE, spec.id)
        assertEquals(1_837_429_010L, spec.archiveBytes)
        assertEquals(null, spec.archiveSha256)
    }
    @Test fun smallAutoPrefersDomesticMirrorWithPinnedRevision() {
        val urls = VoskModelCatalog.urls(VoskModelCatalog.SMALL, VoskModelCatalog.SOURCE_AUTO)
        assertTrue(urls.first().startsWith("https://hf-mirror.com/rhasspy/vosk-models/resolve/"))
        assertEquals(urls.first().replace("https://hf-mirror.com/", "https://huggingface.co/"), urls[1])
        assertTrue(urls.contains(VoskModelCatalog.SMALL.officialUrl))
        assertEquals("https://github.com/BartekReterski/VoskModels/releases/download/v1/vosk-model-small-ru-0.22.zip", urls.last())
        assertEquals(urls.size, urls.distinct().size)
        assertTrue(urls.all { it.startsWith("https://") })
    }
    @Test fun largeDoesNotInventDomesticMirror() {
        val urls = VoskModelCatalog.urls(VoskModelCatalog.LARGE, VoskModelCatalog.SOURCE_DOMESTIC)
        assertEquals(listOf(VoskModelCatalog.LARGE.officialUrl), urls)
        assertFalse(urls.any { it.contains("modelscope") || it.contains("hf-mirror") })
    }
    @Test fun officialSourcePrecedesSmallMirror() {
        assertEquals(VoskModelCatalog.SMALL.officialUrl,
            VoskModelCatalog.urls(VoskModelCatalog.SMALL, VoskModelCatalog.SOURCE_OFFICIAL).first())
    }
    @Test fun customHttpsSourceIsPreferredAndPreservesFallback() {
        val first = VoskModelCatalog.urls(VoskModelCatalog.LARGE,
            VoskModelCatalog.SOURCE_CUSTOM, "https://example.org/ru.zip")
        assertEquals("https://example.org/ru.zip", first.first())
        assertTrue(first.contains(VoskModelCatalog.LARGE.officialUrl))
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsHttpCustomSource() {
        VoskModelCatalog.urls(VoskModelCatalog.SMALL, VoskModelCatalog.SOURCE_CUSTOM,
            "http://example.org/ru.zip")
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownVoskSize() {
        VoskModelCatalog.model("vosk-model-ru-0.22")
    }
}
