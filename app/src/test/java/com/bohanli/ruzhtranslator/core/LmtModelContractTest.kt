package com.bohanli.ruzhtranslator.core

import com.bohanli.ruzhtranslator.settings.AppSettings
import org.junit.Assert.*
import org.junit.Test

class LmtModelContractTest {
    @Test fun routesToSeparateFormatsAndDirectories() {
        val lmt = ModelCatalog.forEngine(AppSettings.ENGINE_LMT)
        val gemma = ModelCatalog.forEngine(AppSettings.ENGINE_GEMMA4)
        assertTrue(lmt.fileName.endsWith(".gguf"))
        assertTrue(gemma.fileName.endsWith(".litertlm"))
        assertNotEquals(lmt.directory, gemma.directory)
        assertNotEquals(lmt.sha256, gemma.sha256)
    }
    @Test fun lmtMirrorPinsTheSameArtifactAsHuggingFace() {
        val hf = ModelCatalog.sourceUrl(AppSettings.SOURCE_HF, AppSettings.ENGINE_LMT)!!
        val mirror = ModelCatalog.sourceUrl(AppSettings.SOURCE_HF_MIRROR, AppSettings.ENGINE_LMT)!!
        assertEquals(hf.replace("huggingface.co", "hf-mirror.com"), mirror)
        assertTrue(hf.contains(ModelCatalog.LMT.revision))
        assertNull(ModelCatalog.sourceUrl(AppSettings.SOURCE_MODELSCOPE, AppSettings.ENGINE_LMT))
    }
    @Test(expected = IllegalArgumentException::class)
    fun unknownEngineNeverSilentlyBecomesGemma() { ModelCatalog.forEngine("not-an-engine") }
    @Test fun rangeMustMatchTheExistingPartial() { assertEquals(1000L, DownloadProtocol.rangeTotal("bytes 100-999/1000", 100L, 900L)) }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsWrongResumeOffset() { DownloadProtocol.rangeTotal("bytes 0-999/1000", 100L, 1000L) }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsWrongRangeLength() { DownloadProtocol.rangeTotal("bytes 100-999/1000", 100L, 899L) }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsPlainHttp() { DownloadProtocol.requireHttps("http://example.com/model.gguf") }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsCredentialsInUrl() { DownloadProtocol.requireHttps("https://user:secret@example.com/model.gguf") }
    @Test fun verifiesSafeUrlAndSizeBounds() {
        DownloadProtocol.requireHttps(ModelCatalog.LMT.url("huggingface.co"))
        assertFalse(ModelCatalog.LMT.acceptsSize(0))
        assertFalse(ModelCatalog.LMT.acceptsSize(ModelCatalog.LMT.maximumBytes + 1))
        assertFalse(ModelCatalog.GEMMA4.acceptsSize(ModelCatalog.GEMMA4_BYTES - 1))
    }
}
