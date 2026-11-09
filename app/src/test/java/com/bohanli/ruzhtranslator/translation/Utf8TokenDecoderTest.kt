package com.bohanli.ruzhtranslator.translation

import org.junit.Assert.assertEquals
import org.junit.Test

class Utf8TokenDecoderTest {
    @Test fun everyPossibleBoundaryPreservesRussianChineseAndEmoji() {
        val text = "Здравствуйте！每吨 1000 卢布，300 吨玉米。😀\u0000"
        val bytes = text.toByteArray(Charsets.UTF_8)
        for (cut in 0..bytes.size) {
            val decoder = Utf8TokenDecoder()
            val output = decoder.append(bytes.copyOfRange(0, cut)) +
                decoder.append(bytes.copyOfRange(cut, bytes.size)) + decoder.finish()
            assertEquals("cut=$cut", text, output)
        }
    }
    @Test fun oneByteTokensAreLossless() {
        val text = "俄语→中文🌽"
        val decoder = Utf8TokenDecoder()
        val output = buildString {
            text.toByteArray(Charsets.UTF_8).forEach { append(decoder.append(byteArrayOf(it))) }
            append(decoder.finish())
        }
        assertEquals(text, output)
    }
    @Test fun unfinishedCharacterIsNotStreamedUntilComplete() {
        val decoder = Utf8TokenDecoder()
        val bytes = "中".toByteArray(Charsets.UTF_8)
        assertEquals("", decoder.append(bytes.copyOfRange(0, 2)))
        assertEquals("中", decoder.append(bytes.copyOfRange(2, 3)))
        assertEquals("", decoder.finish())
    }
}
