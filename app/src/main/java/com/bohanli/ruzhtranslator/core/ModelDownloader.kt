package com.bohanli.ruzhtranslator.core

import android.content.Context
import android.net.Uri
import com.bohanli.ruzhtranslator.settings.AppSettings
import java.io.File
import java.io.FileOutputStream
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
            val transport = ModelDownloadTransport(spec, partial)
            transport.download(urls) { p -> progress(Progress(p.received, p.expected, p.stage)) }
            // The transport has joined all workers and verified the complete catalog digest.
            promote(partial)
            progress(Progress(target.length(), target.length(), "已完成"))
        }
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
        File(dir, "${spec.fileName}.chunks.done").delete()
        File(dir, "${spec.fileName}.chunks.done.tmp").delete()
        markInstalled()
    }
    private fun markInstalled() {
        receipt.writeText(receiptText(target))
        AppSettings.notifyModelsChanged()
    }
    suspend fun deleteModel() = withContext(Dispatchers.IO) {
        installLock.withLock {
            for (file in listOf(target, partial, receipt,
                File(dir, "${spec.fileName}.chunks.done"), File(dir, "${spec.fileName}.chunks.done.tmp"))) {
                check(!file.exists() || file.delete()) { "无法删除 ${file.name}" }
            }
            AppSettings.notifyModelsChanged()
        }
    }
}
