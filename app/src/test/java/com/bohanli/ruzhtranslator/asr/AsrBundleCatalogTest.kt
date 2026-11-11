package com.bohanli.ruzhtranslator.asr

import com.bohanli.ruzhtranslator.settings.AppSettings
import org.junit.Assert.*
import org.junit.Test

class AsrBundleCatalogTest {
    @Test fun modelsMatchKnownAndroidOnnxSizes() {
        assertEquals(987_015_347L, AsrBundleCatalog.QWEN.totalBytes)
        assertEquals(682_215_356L, AsrBundleCatalog.NEMOTRON.totalBytes)
    }
    @Test fun defaultSourceNeverFetchesIncompatibleModelScopeRawWeights() {
        for (bundle in AsrBundleCatalog.all) {
            assertTrue(bundle.nativeModelScope.isNotBlank())
            assertNull(bundle.modelScopeCompatibleRepository)
            for (file in bundle.files) {
                val urls = bundle.sourceUrls(file.path, "modelscope")
                assertEquals(1, urls.size)
                assertTrue(urls.single().startsWith("https://hf-mirror.com/"))
                assertFalse(urls.any { it.contains("huggingface.co") || it.contains("github.com") })
            }
        }
    }
    @Test fun explicitDomesticMirrorHasPriorityWithCheckedFallback() {
        val file = AsrBundleCatalog.QWEN.files[0].path
        val urls = AsrBundleCatalog.QWEN.sourceUrls(file, AppSettings.SOURCE_CUSTOM,
            "https://mirror.example.cn/qwen")
        assertEquals("https://mirror.example.cn/qwen/conv_frontend.onnx", urls.first())
        assertTrue(urls.last().startsWith("https://hf-mirror.com/"))
    }
    @Test(expected=IllegalArgumentException::class)
    fun foreignInsecureCustomUrlRejected() {
        AsrBundleCatalog.NEMOTRON.sourceUrls("tokens.txt", AppSettings.SOURCE_CUSTOM,
            "http://example.cn")
    }
}
