package com.bohanli.ruzhtranslator.core

import com.bohanli.ruzhtranslator.settings.AppSettings
import org.junit.Assert.*
import org.junit.Test

class ModelSourcesTest {
    @Test fun automaticModeNeverStartsOnHuggingFace() {
        for (spec in ModelCatalog.all) {
            val urls = ModelCatalog.downloadUrls(AppSettings.SOURCE_AUTO, spec.engine)
            assertFalse(urls.first().startsWith("https://huggingface.co/"))
            assertEquals(spec.url("huggingface.co"), urls.last())
            assertEquals(urls.size, urls.distinct().size)
            urls.forEach(DownloadProtocol::requireHttps)
        }
    }

    @Test fun verifiedModelScopeArtifactsTakePriorityAndKeepSeparateRevisions() {
        for (spec in listOf(ModelCatalog.GEMMA4, ModelCatalog.HYMT2)) {
            val urls = ModelCatalog.downloadUrls(AppSettings.SOURCE_AUTO, spec.engine)
            assertEquals(spec.modelScopeUrl(), urls[0])
            assertEquals(spec.modelScopeUrl(api = true), urls[1])
            assertNotEquals(spec.revision, spec.modelScopeRevision)
            assertTrue(urls[0].contains(spec.modelScopeRevision!!))
            assertTrue(urls[1].contains(spec.modelScopeRevision!!))
        }
    }

    @Test fun lmtWithoutIdenticalModelScopeGgufStillHasAutomaticFallbacks() {
        val urls = ModelCatalog.downloadUrls(AppSettings.SOURCE_MODELSCOPE, AppSettings.ENGINE_LMT)
        assertNull(ModelCatalog.LMT.modelScopeUrl())
        assertEquals(ModelCatalog.LMT.url("hf-mirror.com"), urls.first())
        assertFalse(urls.any { it.contains("modelscope") })
        assertEquals(1_282_439_264L, ModelCatalog.LMT.bytes)
        assertFalse(ModelCatalog.LMT.acceptsSize(ModelCatalog.LMT.bytes - 1))
    }

    @Test fun userProxyCandidatesWrapTheCompletePinnedHttpsUrl() {
        val spec = ModelCatalog.LMT
        val urls = ModelCatalog.downloadUrls(AppSettings.SOURCE_CN_PROXY, spec.engine)
        assertEquals("https://gh-proxy.org/" + spec.url("huggingface.co"), urls[0])
        assertTrue(urls.contains("https://ghproxy.imciel.com/" + spec.url("huggingface.co")))
        assertTrue(urls.contains("https://gh-proxy.com/" + spec.url("huggingface.co")))
        val excluded = listOf("ghfast.top", "hub.gitmirror.com", "gh.llkk.cc", "github.moeyy.xyz")
        assertFalse(urls.any { url -> excluded.any(url::contains) })
    }

    @Test fun explicitSourceIsAPreferenceAndDoesNotDisableFailover() {
        val urls = ModelCatalog.downloadUrls(AppSettings.SOURCE_HF, AppSettings.ENGINE_GEMMA4)
        assertEquals(ModelCatalog.GEMMA4.url("huggingface.co"), urls.first())
        assertTrue(urls.contains(ModelCatalog.GEMMA4.modelScopeUrl()))
        assertTrue(urls.contains(ModelCatalog.GEMMA4.url("hf-mirror.com")))
    }

    @Test fun customUrlPreservedBeforeAllKnownFallbacksWithoutDuplicates() {
        val custom = ModelCatalog.GEMMA4.modelScopeUrl()!!
        val urls = ModelCatalog.downloadUrls(AppSettings.SOURCE_CUSTOM, AppSettings.ENGINE_GEMMA4, "  $custom  ")
        assertEquals(custom, urls.first())
        assertEquals(1, urls.count { it == custom })
        assertTrue(urls.size > 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankCustomUrlRequiresAnActionableError() {
        ModelCatalog.downloadUrls(AppSettings.SOURCE_CUSTOM, AppSettings.ENGINE_GEMMA4, " ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsafeCustomUrlNeverEntersTheFailoverQueue() {
        ModelCatalog.downloadUrls(AppSettings.SOURCE_CUSTOM, AppSettings.ENGINE_LMT, "http://example.org/model.gguf")
    }
}
