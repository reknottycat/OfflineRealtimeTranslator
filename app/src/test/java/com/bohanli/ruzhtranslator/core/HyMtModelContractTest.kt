package com.bohanli.ruzhtranslator.core

import com.bohanli.ruzhtranslator.settings.AppSettings
import org.junit.Assert.*
import org.junit.Test

class HyMtModelContractTest {
    @Test fun routesToOfficialPinnedHyModel() {
        val hy = ModelCatalog.forEngine(AppSettings.ENGINE_HYMT2)
        assertEquals("tencent/Hy-MT2-1.8B-GGUF", hy.repository)
        assertEquals("Hy-MT2-1.8B-Q4_K_M.gguf", hy.fileName)
        assertEquals(1_133_080_448L, hy.bytes)
        assertEquals("dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699", hy.sha256)
    }
    @Test fun eachEngineOwnsItsDirectoryAndHash() {
        val specs = listOf(ModelCatalog.GEMMA4, ModelCatalog.LMT, ModelCatalog.HYMT2)
        assertEquals(3, specs.map { it.directory }.toSet().size)
        assertEquals(3, specs.map { it.sha256 }.toSet().size)
        assertEquals(3, specs.map { it.fileName }.toSet().size)
    }
    @Test fun nativeProfilesCannotAliasGemmaOrEachOther() {
        assertNull(ModelCatalog.GEMMA4.nativeProfile)
        assertEquals(0, ModelCatalog.LMT.nativeProfile)
        assertEquals(1, ModelCatalog.HYMT2.nativeProfile)
    }
    @Test fun bothSourcesPinIdenticalHyRevisionAndFile() {
        val hf = ModelCatalog.sourceUrl(AppSettings.SOURCE_HF, AppSettings.ENGINE_HYMT2)!!
        val mirror = ModelCatalog.sourceUrl(AppSettings.SOURCE_HF_MIRROR, AppSettings.ENGINE_HYMT2)!!
        assertEquals(hf.replace("huggingface.co", "hf-mirror.com"), mirror)
        assertTrue(hf.contains("a0c709d9fac510f2c807aa3af52872340dc37a4a"))
    }
    @Test fun incompleteOrWrongSizeHyFileCannotInstall() {
        val hy = ModelCatalog.HYMT2
        assertTrue(hy.acceptsSize(hy.bytes))
        assertFalse(hy.acceptsSize(hy.bytes - 1))
        assertFalse(hy.acceptsSize(hy.bytes + 1))
        assertFalse(hy.acceptsSize(ModelCatalog.GEMMA4_BYTES))
    }
    @Test fun modelScopeIsNotMisrepresentedAsPreverifiedAutomaticSource() {
        assertNull(ModelCatalog.sourceUrl(AppSettings.SOURCE_MODELSCOPE, AppSettings.ENGINE_HYMT2))
    }
    @Test(expected = IllegalArgumentException::class)
    fun oldOrMistypedHyNameDoesNotSilentlySelectAnotherEngine() { ModelCatalog.forEngine("hymt") }
}
