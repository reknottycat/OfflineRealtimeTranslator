package com.bohanli.ruzhtranslator.translation

import androidx.annotation.Keep
import com.bohanli.ruzhtranslator.core.ModelCatalog
import com.bohanli.ruzhtranslator.settings.AppSettings
import java.io.File

/** Shared JNI transport only. LMT and HY-MT2 use separate weights, architectures and prompts. */
@Keep
class LmtNativeEngine(engineId: String = AppSettings.ENGINE_LMT) {
    companion object {
        private val loaded: Boolean by lazy {
            try { System.loadLibrary("lmt_translator"); true }
            catch (e: LinkageError) { throw IllegalStateException("GGUF native library is unavailable", e) }
        }
    }
    private val kind = ModelCatalog.nativeKind(engineId)
    private val title = ModelCatalog.forEngine(engineId).title
    private val lifetime = Any()
    private var handle = 0L
    private var closing = false
    private var decoder = Utf8TokenDecoder()
    private var stream: ((String) -> Unit)? = null
    private external fun nativeCreate(path: String, kind: Int): Long
    private external fun nativeTranslate(handle: Long, sourceUtf8: ByteArray): ByteArray
    private external fun nativeCancel(handle: Long)
    private external fun nativeDestroy(handle: Long)
    fun initialize(file: File) {
        check(loaded)
        val created = nativeCreate(file.absolutePath, kind)
        check(created != 0L) { "$title model initialization failed" }
        synchronized(lifetime) {
            handle = created
            if (closing) nativeCancel(created)
        }
    }
    fun translate(text: String, onChunk: (String) -> Unit): String {
        val current = synchronized(lifetime) {
            check(!closing && handle != 0L) { "$title engine is closed" }
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
