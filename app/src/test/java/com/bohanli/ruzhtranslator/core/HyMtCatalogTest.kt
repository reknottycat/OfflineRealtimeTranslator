package com.bohanli.ruzhtranslator.core

import com.bohanli.ruzhtranslator.settings.AppSettings
import org.junit.Assert.*
import org.junit.Test

class HyMtCatalogTest {
    @Test fun allThreeEnginesHaveDistinctStorageAndHashes() {
        assertEquals(3, ModelCatalog.all.size)
        assertEquals(3, ModelCatalog.all.map { it.directory }.distinct().size)
        assertEquals(3, ModelCatalog.all.map { it.sha256 }.distinct().size)
        assertEquals(3, ModelCatalog.all.map { it.fileName }.distinct().size)
    }
    @Test fun hyMtUsesOfficialRepositoryAndPinnedRevision() {
        val spec = ModelCatalog.forEngine(AppSettings.ENGINE_HYMT2)
        assertEquals("tencent/Hy-MT2-1.8B-GGUF", spec.repository)
        assertEquals("a0c709d9fac510f2c807aa3af52872340dc37a4a", spec.revision)
        assertEquals("Hy-MT2-1.8B-Q4_K_M.gguf", spec.fileName)
        assertEquals("dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699", spec.sha256)
    }
    @Test fun hyMtRejectsIncompleteOrWrongSizeWeights() {
        assertTrue(ModelCatalog.HYMT2.acceptsSize(1_133_080_448L))
        assertFalse(ModelCatalog.HYMT2.acceptsSize(1_133_080_447L))
        assertFalse(ModelCatalog.HYMT2.acceptsSize(1_133_080_449L))
        assertFalse(ModelCatalog.HYMT2.acceptsSize(0))
        assertFalse(ModelCatalog.HYMT2.acceptsSize(ModelCatalog.GEMMA4_BYTES))
    }
    @Test fun mirrorsUseIdenticalPinnedContent() {
        val spec = ModelCatalog.HYMT2
        assertEquals(spec.url("huggingface.co").substringAfter(".co/"),
            spec.url("hf-mirror.com").substringAfter(".com/"))
        assertEquals(spec.url("huggingface.co"), ModelCatalog.sourceUrl(AppSettings.SOURCE_HF, spec.engine))
        assertNull(ModelCatalog.sourceUrl(AppSettings.SOURCE_MODELSCOPE, spec.engine))
    }
    @Test fun nativeProfileIdsCannotAliasGemmaOrEachOther() {
        assertEquals(0, ModelCatalog.nativeKind(AppSettings.ENGINE_LMT))
        assertEquals(1, ModelCatalog.nativeKind(AppSettings.ENGINE_HYMT2))
    }
    @Test(expected = IllegalArgumentException::class) fun gemmaCannotUseGgufBridge() {
        ModelCatalog.nativeKind(AppSettings.ENGINE_GEMMA4)
    }
    @Test(expected = IllegalArgumentException::class) fun unknownEngineRejected() {
        ModelCatalog.forEngine("hy-mt-misspelling")
    }
    @Test fun existingModelFilesRemainUnchanged() {
        assertEquals("gemma-4-E2B-it.litertlm", ModelCatalog.GEMMA4.fileName)
        assertEquals("LMT-60-1.7B.Q4_K_M.gguf", ModelCatalog.LMT.fileName)
        assertEquals("lmt-60-1.7b-q4_k_m", ModelCatalog.LMT.directory)
    }
}
