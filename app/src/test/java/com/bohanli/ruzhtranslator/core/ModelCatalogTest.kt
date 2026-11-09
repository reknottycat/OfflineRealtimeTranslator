package com.bohanli.ruzhtranslator.core

import com.bohanli.ruzhtranslator.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {
    @Test fun officialSourceUsesPinnedImmutableRevision() {
        val url = ModelCatalog.sourceUrl(AppSettings.SOURCE_HF)!!
        assertTrue(url.startsWith("https://huggingface.co/"))
        assertTrue(url.contains(ModelCatalog.HF_REVISION))
        assertTrue(url.endsWith(ModelCatalog.GEMMA4_FILE))
    }
    @Test fun mirrorUsesSamePinnedModelPath() {
        val official = ModelCatalog.sourceUrl(AppSettings.SOURCE_HF)!!
        val mirror = ModelCatalog.sourceUrl(AppSettings.SOURCE_HF_MIRROR)!!
        assertEquals(official.substringAfter(".co/"), mirror.substringAfter(".com/"))
    }
    @Test fun unverifiedModelScopeUrlMustBeSuppliedByUser() {
        assertNull(ModelCatalog.sourceUrl(AppSettings.SOURCE_MODELSCOPE))
    }
    @Test fun modelHashHasCorrectFormat() {
        assertEquals(64, ModelCatalog.GEMMA4_SHA256.length)
        assertTrue(ModelCatalog.GEMMA4_BYTES > 2_000_000_000L)
    }
}
