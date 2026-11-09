package com.bohanli.ruzhtranslator.core

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Download/verify a pinned multi-GB model to app-private storage.
 * Supports HTTP Range resume, atomic rename, and streaming SHA-256 verification.
 * Never uses MANAGE_EXTERNAL_STORAGE, never bundles weights in the APK.
 */
class ModelDownloader(private val context: Context) {
    data class Progress(val received: Long, val expected: Long, val stage: String)

    private val modelDir: File
        get() = File(context.filesDir, "models/${ModelCatalog.GEMMA4_ID}")

    fun installed(): Boolean {
        val file = File(modelDir, ModelCatalog.GEMMA4_FILE)
        return file.isFile && file.length() == ModelCatalog.GEMMA4_BYTES
    }

    suspend fun download(url: String, progress: (Progress) -> Unit) = withContext(Dispatchers.IO) {
        val uri = Uri.parse(url)
        require(uri.scheme == "https" && !uri.host.isNullOrEmpty()) {
            "Only HTTPS model downloads are accepted"
        }
        val dir = modelDir
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create app-private model directory" }
        val target = File(dir, ModelCatalog.GEMMA4_FILE)
        val partial = File(dir, "${ModelCatalog.GEMMA4_FILE}.part")
        var offset = partial.takeIf { it.exists() }?.length() ?: 0L
        if (offset == ModelCatalog.GEMMA4_BYTES) {
            if (verify(partial, progress)) {
                if (target.exists()) target.delete()
                check(partial.renameTo(target)) { "Cannot finalize downloaded model" }
                progress(Progress(target.length(), ModelCatalog.GEMMA4_BYTES, "已完成"))
                return@withContext
            }
            partial.delete() // A complete but corrupt partial must not yield endless HTTP 416.
            offset = 0L
        } else if (offset > ModelCatalog.GEMMA4_BYTES) {
            partial.delete()
            offset = 0L
        }
        if (target.isFile && verify(target, progress)) return@withContext
        if (target.exists()) target.delete()

        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept-Encoding", "identity")
            conn.setRequestProperty("User-Agent", "OfflineRealtimeTranslator/3.3")
            if (offset > 0L) conn.setRequestProperty("Range", "bytes=$offset-")
            conn.connect()
            require(conn.url.protocol == "https") { "Insecure redirect refused" }
            val response = conn.responseCode
            require(response == 200 || response == 206) {
                "Download server returned HTTP $response"
            }
            val resumed = offset > 0L && response == 206
            if (resumed) {
                val range = conn.getHeaderField("Content-Range") ?: ""
                require(range.startsWith("bytes $offset-")) {
                    "Invalid resume Content-Range: $range"
                }
            }
            if (!resumed) {
                offset = 0L
                if (partial.exists()) partial.delete()
            }
            val responseLength = conn.contentLengthLong
            if (responseLength > 0) {
                require(offset + responseLength == ModelCatalog.GEMMA4_BYTES) {
                    "Unexpected model size: ${offset + responseLength} bytes"
                }
            }
            var received = offset
            val chunk = ByteArray(256 * 1024)
            var lastReported = received
            conn.inputStream.use { input ->
                FileOutputStream(partial, resumed).use { output ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(chunk)
                        if (count < 0) break
                        output.write(chunk, 0, count)
                        received += count
                        require(received <= ModelCatalog.GEMMA4_BYTES) {
                            "Oversized model response"
                        }
                        if (received - lastReported >= 2 * 1024 * 1024L) {
                            progress(Progress(received, ModelCatalog.GEMMA4_BYTES, "下载中"))
                            lastReported = received
                        }
                    }
                    output.fd.sync()
                }
            }
            require(partial.length() == ModelCatalog.GEMMA4_BYTES) {
                "Download incomplete. Resume later: ${partial.length()} bytes"
            }
        } finally {
            conn.disconnect()
        }
        if (!verify(partial, progress)) {
            partial.delete()
            error("SHA-256 mismatch; corrupt model deleted")
        }
        check(partial.renameTo(target)) { "Failed to finalize model file" }
        progress(Progress(target.length(), ModelCatalog.GEMMA4_BYTES, "已完成"))
    }

    suspend fun importFrom(uri: Uri, progress: (Progress) -> Unit) = withContext(Dispatchers.IO) {
        val dir = modelDir
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create model directory" }
        val partial = File(dir, "${ModelCatalog.GEMMA4_FILE}.import")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: error("Cannot open selected file")
            input.use {
                FileOutputStream(partial, false).use { output ->
                    val chunk = ByteArray(256 * 1024)
                    var countTotal = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val size = input.read(chunk)
                        if (size < 0) break
                        output.write(chunk, 0, size)
                        countTotal += size
                        require(countTotal <= ModelCatalog.GEMMA4_BYTES) { "File exceeds expected size" }
                        if (countTotal % (8 * 1024 * 1024) < chunk.size) {
                            progress(Progress(countTotal, ModelCatalog.GEMMA4_BYTES, "导入中"))
                        }
                    }
                    output.fd.sync()
                }
            }
            check(verify(partial, progress)) { "Selected file is not the pinned Gemma 4 bundle" }
            val dest = File(dir, ModelCatalog.GEMMA4_FILE)
            if (dest.exists()) check(dest.delete()) { "Cannot replace installed model" }
            check(partial.renameTo(dest)) { "Cannot move imported model" }
            progress(Progress(dest.length(), ModelCatalog.GEMMA4_BYTES, "导入完成"))
        } finally {
            if (partial.exists()) partial.delete()
        }
    }

    suspend fun deleteModel() = withContext(Dispatchers.IO) {
        File(modelDir, ModelCatalog.GEMMA4_FILE).delete()
        File(modelDir, "${ModelCatalog.GEMMA4_FILE}.part").delete()
    }

    private fun verify(file: File, progress: (Progress) -> Unit): Boolean {
        if (file.length() != ModelCatalog.GEMMA4_BYTES) return false
        val digest = MessageDigest.getInstance("SHA-256")
        var countTotal = 0L
        FileInputStream(file).use { stream ->
            val chunk = ByteArray(512 * 1024)
            while (true) {
                currentCoroutineContextOrThrow()
                val count = stream.read(chunk)
                if (count < 0) break
                digest.update(chunk, 0, count)
                countTotal += count
                if (countTotal % (64 * 1024 * 1024) < chunk.size) {
                    progress(Progress(countTotal, ModelCatalog.GEMMA4_BYTES, "校验 SHA-256"))
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } ==
            ModelCatalog.GEMMA4_SHA256
    }

    private fun currentCoroutineContextOrThrow() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Verification interrupted")
    }
}
