package com.bohanli.ruzhtranslator.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultilingualAsrSourcesTest {
    @Test fun everyCpuModelFileKeepsMirrorBeforeHuggingFaceOriginal() {
        for (id in listOf(MultilingualAsrCatalog.NEMOTRON, MultilingualAsrCatalog.QWEN)) {
            val entry = checkNotNull(MultilingualAsrCatalog.forId(id))
            for (file in entry.files) {
                val urls = entry.urls(file)
                val path = if (file.directory == entry.tokenizerDirectory) {
                    "tokenizer/${file.fileName}"
                } else file.fileName
                val pinnedPath = "${file.repository}/resolve/${file.revision}/$path"
                assertEquals("https://hf-mirror.com/$pinnedPath", urls[urls.lastIndex - 1])
                assertEquals("https://huggingface.co/$pinnedPath", urls.last())
                assertEquals(urls.size, urls.distinct().size)
                if (id == MultilingualAsrCatalog.QWEN) {
                    assertEquals(4, urls.size)
                    assertTrue(urls.take(2).all { it.startsWith("https://modelscope.cn/") })
                } else {
                    assertEquals(2, urls.size)
                }
            }
        }
    }
}
