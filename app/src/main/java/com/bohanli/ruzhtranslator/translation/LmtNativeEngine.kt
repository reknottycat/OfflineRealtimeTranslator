package com.bohanli.ruzhtranslator.translation

import androidx.annotation.Keep
import java.io.File

/** All operations except cancel run on the enclosing translator's single owner thread. */
@Keep
class LmtNativeEngine(private val profile: Int = 0) {
    init { require(profile in 0..1) { "Unknown native translation profile" } }
    companion object {
        private val loaded: Boolean by lazy {
            try { System.loadLibrary("lmt_translator"); true }
            catch (e: LinkageError) { throw IllegalStateException("LMT native library is unavailable", e) }
        }
    }
    private val lifetime = Any()
    private var handle = 0L
    private var closing = false
    private var decoder = Utf8TokenDecoder()
    private var stream: ((String) -> Unit)? = null

    private external fun nativeCreate(path: String, profile: Int): Long
    private external fun nativeTranslate(handle: Long, sourceUtf8: ByteArray): ByteArray
    private external fun nativeCancel(handle: Long)
    private external fun nativeDestroy(handle: Long)

    fun initialize(file: File) {
        check(loaded)
        val created = nativeCreate(file.absolutePath, profile)
        check(created != 0L) { "LMT model initialization failed" }
        synchronized(lifetime) {
            handle = created
            if (closing) nativeCancel(created)
        }
    }

    fun translate(text: String, onChunk: (String) -> Unit): String {
        val current = synchronized(lifetime) {
            check(!closing && handle != 0L) { "LMT engine is closed" }
            handle
        }
        decoder = Utf8TokenDecoder()
        stream = onChunk
        try {
            val result = nativeTranslate(current, text.toByteArray(Charsets.UTF_8))
            val tail = decoder.finish()
            if (tail.isNotEmpty()) stream?.invoke(tail)
            return result.toString(Charsets.UTF_8).trim()
        } finally { stream = null }
    }

    /** JNI passes ordinary UTF-8 bytes, never JNI's modified UTF-8 strings. */
    @Keep
    fun onNativeBytes(bytes: ByteArray) {
        val text = decoder.append(bytes)
        if (text.isNotEmpty()) stream?.invoke(text)
    }

    fun cancel() = synchronized(lifetime) {
        closing = true
        if (handle != 0L) nativeCancel(handle)
    }

    fun close() = synchronized(lifetime) {
        closing = true
        if (handle != 0L) { nativeDestroy(handle); handle = 0L }
    }
}
