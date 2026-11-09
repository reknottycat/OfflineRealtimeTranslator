package com.bohanli.ruzhtranslator.core

import android.content.Context
import android.net.Uri
import com.bohanli.ruzhtranslator.settings.AppSettings
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Per-model resumable installer; only verified bytes are ever promoted to a model path. */
class ModelDownloader(context: Context, val spec: ModelCatalog.Spec = ModelCatalog.GEMMA4) {
    companion object { private val installLock = Mutex() }
    private val app = context.applicationContext
    private val dir get() = File(app.filesDir, "models/${spec.directory}")
    private val target get() = File(dir, spec.fileName)
    private val partial get() = File(dir, "${spec.fileName}.part")
    private val receipt get() = File(dir, "${spec.fileName}.verified")
    data class Progress(val received: Long, val expected: Long, val stage: String)

    private fun receiptText(file: File) = "${spec.sha256}:${file.length()}:${file.lastModified()}"
    fun installed(): Boolean = runCatching {
        target.isFile && spec.acceptsSize(target.length()) && receipt.readText() == receiptText(target)
    }.getOrDefault(false)

    suspend fun download(url: String, progress: (Progress) -> Unit) = download(listOf(url), progress)

    suspend fun download(urls: List<String>, progress: (Progress) -> Unit) = withContext(Dispatchers.IO) {
        installLock.withLock {
            require(urls.isNotEmpty()) { "No download source selected" }
            urls.forEach { DownloadProtocol.requireHttps(it) }
            check(dir.isDirectory || dir.mkdirs()) { "Cannot create model directory" }
            if (target.isFile && verify(target, progress)) {
                markInstalled(); return@withLock
            }
            // Also completes an interrupted finalization, avoiding an endless HTTP 416 loop.
            if (partial.isFile && verify(partial, progress)) {
                promote(partial); return@withLock
            }
            var last: Exception? = null
            for (url in urls) {
                currentCoroutineContext().ensureActive()
                try {
                    downloadOne(url, progress)
                    if (!verify(partial, progress)) {
                        partial.delete()
                        error("SHA-256 不匹配，已删除损坏文件；原已安装模型保持不变")
                    }
                    promote(partial)
                    progress(Progress(target.length(), target.length(), "已完成"))
                    return@withLock
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { last = e }
            }
            throw IllegalStateException("所有所选下载源均未完成：${last?.message}", last)
        }
    }

    private suspend fun downloadOne(url: String, progress: (Progress) -> Unit) {
        if (partial.length() > spec.maximumBytes) partial.delete()
        var offset = partial.length()
        var conn = connect(url, offset)
        if (conn.responseCode == 416) {
            conn.disconnect()
            partial.delete()
            offset = 0L
            conn = connect(url, 0L)
        }
        try {
            val status = conn.responseCode
            require(status == 200 || status == 206) { "HTTP $status" }
            val resumed = status == 206
            val length = conn.contentLengthLong
            val total = if (resumed) DownloadProtocol.rangeTotal(conn.getHeaderField("Content-Range"), offset, length)
                else length
            if (!resumed) offset = 0L // Server ignored Range: restart, never append a full file.
            if (total > 0L) require(spec.acceptsSize(total)) { "模型大小不符合目录记录" }
            val remaining = if (total > 0) total - offset else spec.maximumBytes - offset
            require(dir.usableSpace >= remaining + 32L * 1024 * 1024) { "剩余存储空间不足" }
            var received = offset
            var reported = received
            conn.inputStream.use { input ->
                FileOutputStream(partial, resumed).use { output ->
                    val chunk = ByteArray(256 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val size = input.read(chunk)
                        if (size < 0) break
                        require(received + size <= spec.maximumBytes) { "下载文件超出允许大小" }
                        output.write(chunk, 0, size)
                        received += size
                        if (received - reported >= 2L * 1024 * 1024) {
                            progress(Progress(received, if (total > 0L) total else spec.bytes, "下载中"))
                            reported = received
                        }
                    }
                    output.fd.sync()
                }
            }
            if (total > 0L) require(received == total) { "下载中断，已保留断点" }
        } finally { conn.disconnect() }
    }

    private fun connect(initial: String, offset: Long): HttpURLConnection {
        var url = URL(initial)
        repeat(8) {
            DownloadProtocol.requireHttps(url.toString())
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept-Encoding", "identity")
            conn.setRequestProperty("User-Agent", "OfflineRealtimeTranslator/3.4")
            if (offset > 0L) conn.setRequestProperty("Range", "bytes=$offset-")
            try {
                if (conn.responseCode !in listOf(301, 302, 303, 307, 308)) return conn
                val location = conn.getHeaderField("Location") ?: error("Missing redirect Location")
                url = URL(url, location)
            } catch (e: Exception) { conn.disconnect(); throw e }
            conn.disconnect()
        }
        error("Too many model download redirects")
    }

    suspend fun importFrom(uri: Uri, progress: (Progress) -> Unit) = withContext(Dispatchers.IO) {
        installLock.withLock {
            check(dir.isDirectory || dir.mkdirs()) { "Cannot create model directory" }
            val incoming = File(dir, "${spec.fileName}.import")
            try {
                app.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Cannot read selected model" }
                    FileOutputStream(incoming).use { output ->
                        val chunk = ByteArray(256 * 1024)
                        var received = 0L; var reported = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val size = input.read(chunk)
                            if (size < 0) break
                            require(received + size <= spec.maximumBytes) { "文件过大，不是所选模型" }
                            output.write(chunk, 0, size); received += size
                            if (received - reported >= 8L * 1024 * 1024) {
                                progress(Progress(received, spec.bytes, "导入中")); reported = received
                            }
                        }
                        output.fd.sync()
                    }
                }
                check(verify(incoming, progress)) { "模型校验失败：请选择 ${spec.fileName}，不能混用两个引擎的权重" }
                promote(incoming)
                progress(Progress(target.length(), target.length(), "导入完成"))
            } finally { incoming.delete() }
        }
    }

    private suspend fun verify(file: File, progress: (Progress) -> Unit): Boolean {
        if (!file.isFile || !spec.acceptsSize(file.length())) return false
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L; var reported = 0L
        file.inputStream().use { input ->
            val chunk = ByteArray(512 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val size = input.read(chunk)
                if (size < 0) break
                digest.update(chunk, 0, size); count += size
                if (count - reported >= 64L * 1024 * 1024) {
                    progress(Progress(count, file.length(), "校验 SHA-256")); reported = count
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == spec.sha256
    }

    private suspend fun promote(incoming: File) {
        currentCoroutineContext().ensureActive()
        // Same-directory atomic replacement: never truncate/unlink the old model first.
        Files.move(incoming.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        markInstalled()
    }
    private fun markInstalled() {
        receipt.writeText(receiptText(target))
        AppSettings.notifyModelsChanged()
    }
    suspend fun deleteModel() = withContext(Dispatchers.IO) {
        installLock.withLock {
            for (file in listOf(target, partial, receipt)) {
                check(!file.exists() || file.delete()) { "无法删除 ${file.name}" }
            }
            AppSettings.notifyModelsChanged()
        }
    }
}
