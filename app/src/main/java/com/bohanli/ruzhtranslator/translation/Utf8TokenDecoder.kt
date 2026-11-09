package com.bohanli.ruzhtranslator.translation

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** Token boundaries need not coincide with UTF-8 character boundaries. */
class Utf8TokenDecoder {
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pending = ByteArray(0)

    fun append(bytes: ByteArray): String = decode(bytes, false)
    fun finish(): String = decode(ByteArray(0), true)

    private fun decode(bytes: ByteArray, last: Boolean): String {
        val buffer = ByteBuffer.wrap(pending + bytes)
        val output = CharBuffer.allocate(buffer.remaining() * 2 + 8)
        val result = decoder.decode(buffer, output, last)
        check(!result.isOverflow) { "UTF-8 output buffer overflow" }
        if (result.isError) result.throwException()
        pending = ByteArray(buffer.remaining()).also { buffer.get(it) }
        if (last) decoder.flush(output)
        output.flip()
        return output.toString()
    }
}
