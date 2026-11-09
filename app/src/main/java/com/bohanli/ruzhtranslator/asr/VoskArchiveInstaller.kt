package com.bohanli.ruzhtranslator.asr

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.bohanli.ruzhtranslator.settings.AppSettings
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Safely install a zipped Vosk model in app-private storage.
 *
 * - Resumes via HTTP Range, rotates HTTPS sources and never mixes a 200 response
 *   into a partial file.
 * - Small is authenticated with the HF LFS SHA-256; Large has no confirmed
 *   publisher SHA-256, so it is restricted to HTTPS + exact size + ZIP CRCs
 *   + required Vosk structure (a weaker guarantee, disclosed in the UI).
 * - Extraction is transactional and bounded to stop zip-slip and zip bombs.
 * - Partial archives survive cancellation; switching the active model never
 *   deletes the old model or history.
 */
class VoskArchiveInstaller(
    context: Context,
    val spec: VoskModelCatalog.Spec
) {
    data class Progress(val bytes: Long, val total: Long, val stage: String)
    companion object { private val modelLock = Mutex() }
    private val app = context.applicationContext
    private val modelsRoot = File(app.filesDir, "models")
    private val destination = File(modelsRoot, spec.id)
    private val workDir = File(app.filesDir, "vosk_downloads")
    private val partial = File(workDir, spec.archiveName + ".part")
    private val partialOrigin = File(workDir, spec.archiveName + ".origin")
    private val importFile = File(workDir, spec.archiveName + ".import")
    private val staging = File(modelsRoot, "." + spec.id + ".installing")
    private val previous = File(modelsRoot, "." + spec.id + ".backup")

    fun installed(): Boolean = isValidVoskDirectory(destination)

    suspend fun download(urls: List<String>, report: (Progress) -> Unit) = withContext(Dispatchers.IO) {
        modelLock.withLock {
            require(urls.isNotEmpty()) { "没有可用的下载源" }
            urls.forEach { require(URL(it).protocol.equals("https", true)) { "仅允许 HTTPS 模型源" } }
            makeDirectories()
            if (installed()) return@withLock
            ensureSpace()
            if (partial.length() == spec.archiveBytes) {
                if (!verifyArchive(partial, report)) partial.delete()
                else { unpackAndInstall(partial, report); return@withLock }
            }
            var error: Exception? = null
            for ((index, source) in urls.withIndex()) {
                currentCoroutineContext().ensureActive()
                try {
                    prepareSource(source, report)
                    report(Progress(partial.length(), spec.archiveBytes,
                        "下载来源 " + (index + 1) + "/" + urls.size + " · " + URL(source).host))
                    fetch(source, report)
                    if (!verifyArchive(partial, report)) {
                        partial.delete()
                        partialOrigin.delete()
                        throw IOException("Vosk ZIP SHA-256 不匹配：文件可能不是所选版本，已清除损坏断点")
                    }
                    unpackAndInstall(partial, report)
                    return@withLock
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (e: Exception) {
                    error = e
                    report(Progress(partial.length(), spec.archiveBytes,
                        "来源失败：" + (e.message ?: e.javaClass.simpleName) + "，尝试备用源"))
                }
            }
            throw IOException("下载失败，已保留可恢复的断点：" + (error?.message ?: "未知原因"))
        }
    }

    suspend fun importZip(uri: Uri, report: (Progress) -> Unit) = withContext(Dispatchers.IO) {
        modelLock.withLock {
            makeDirectories()
            ensureSpace(forNewArchive = true)
            importFile.delete()
            try {
                app.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "无法读取选择的 ZIP 文件" }
                    FileOutputStream(importFile).use { output ->
                        val buffer = ByteArray(256 * 1024)
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            require(total <= spec.archiveBytes) {
                                "导入文件过大，不是所选 Vosk ZIP。官方 Large ZIP 约 1.84 GB，原仓库约 3.5 GB 是已解压文件夹，请点击「导入已解压 Vosk 文件夹」"
                            }
                            output.write(buffer, 0, n)
                            report(Progress(total, spec.archiveBytes, "导入 Vosk ZIP"))
                        }
                        output.fd.sync()
                    }
                }
                check(verifyArchive(importFile, report)) {
                    "ZIP 文件校验不符：需要准确的 " + spec.archiveName +
                        "（压缩包 " + spec.archiveBytes + " 字节）。已解压模型请选择「导入文件夹」"
                }
                unpackAndInstall(importFile, report)
            } finally { importFile.delete() }
        }
    }

    /**
     * Import the original project's already extracted 3.5–3.7 GB model folder
     * using Android's Storage Access Framework. The user may select the model
     * directory itself, or its parent containing the exact model directory.
     */
    suspend fun importDirectory(treeUri: Uri, report: (Progress) -> Unit) = withContext(Dispatchers.IO) {
        modelLock.withLock {
            makeDirectories()
            check(!previous.exists()) { "发现以前安装中断留下的备份，未覆盖原模型" }
            val chosen = requireNotNull(DocumentFile.fromTreeUri(app, treeUri)) {
                "无法访问选择的 Vosk 模型文件夹"
            }
            val selected = if (chosen.findFile("am")?.isDirectory == true) chosen
                else chosen.findFile(spec.id)?.takeIf { it.isDirectory }
                    ?: throw IOException("请选择包含 am、conf、graph 的 " + spec.id + " 文件夹")
            check(modelsRoot.usableSpace >= spec.estimatedExpandedBytes + 64L * 1024 * 1024) {
                "手机空间不足以导入约 3.5–3.7 GB 的已解压 Vosk 模型"
            }
            staging.deleteRecursively()
            check(staging.mkdirs()) { "创建解压模型临时目录失败" }
            val state = longArrayOf(0L, 0L) // cumulative bytes, file count
            try {
                copyDocumentTree(selected, staging, state, report)
                check(isValidVoskDirectory(staging)) {
                    "模型文件夹不是可加载的 " + spec.id +
                        "：缺少 am/final.mdl、conf/mfcc.conf 或 graph/*.fst"
                }
                promoteStaging()
                report(Progress(state[0], state[0], "已导入解压后的 Vosk 模型文件夹"))
            } finally {
                if (staging.exists()) staging.deleteRecursively()
            }
        }
    }

    private suspend fun copyDocumentTree(
        source: DocumentFile, target: File, state: LongArray, report: (Progress) -> Unit
    ) {
        for (child in source.listFiles()) {
            currentCoroutineContext().ensureActive()
            val name = child.name ?: throw IOException("选中的目录存在无文件名的条目")
            check(name.isNotBlank() && name != "." && name != ".." &&
                !name.contains('/') && !name.contains('\\') && !name.contains(':')) {
                "导入文件夹包含不安全的文件名：" + name
            }
            state[1]++
            check(state[1] <= 20_000L) { "Vosk 文件数量异常" }
            val output = File(target, name)
            if (child.isDirectory) {
                check(output.isDirectory || output.mkdirs()) { "创建 Vosk 子目录失败" }
                copyDocumentTree(child, output, state, report)
            } else {
                check(child.isFile) { "Vosk 模型目录包含不支持的类型" }
                val expected = child.length()
                check(expected <= spec.maxExpandedBytes) { "模型单个文件过大" }
                app.contentResolver.openInputStream(child.uri).use { input ->
                    requireNotNull(input) { "无法读取：" + name }
                    FileOutputStream(output).use { sink ->
                        val buffer = ByteArray(256 * 1024)
                        var copied = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            copied += n
                            state[0] += n
                            check(state[0] <= spec.maxExpandedBytes) { "解压模型内容超过安全上限" }
                            sink.write(buffer, 0, n)
                        }
                        sink.fd.sync()
                        if (expected > 0) check(copied == expected) {
                            "已解压模型文件复制不完整：" + name
                        }
                    }
                }
                report(Progress(state[0], spec.estimatedExpandedBytes, "导入文件夹 · " + name))
            }
        }
    }

    suspend fun remove() = withContext(Dispatchers.IO) {
        modelLock.withLock {
            // Never delete public Downloads or other apps' models.
            require(!previous.exists()) { "检测到安装备份，请先人工检查以避免数据丢失" }
            destination.deleteRecursively()
            partial.delete()
            partialOrigin.delete()
            importFile.delete()
            AppSettings.notifyModelsChanged()
        }
    }

    private fun makeDirectories() {
        check((modelsRoot.isDirectory || modelsRoot.mkdirs()) &&
            (workDir.isDirectory || workDir.mkdirs())) { "无法创建模型目录" }
    }

    private fun ensureSpace(forNewArchive: Boolean = false) {
        val remainder = if (forNewArchive) spec.archiveBytes
            else (spec.archiveBytes - partial.length()).coerceAtLeast(0)
        val minimum = remainder + spec.estimatedExpandedBytes + 64L * 1024 * 1024
        check(workDir.usableSpace >= minimum) {
            "存储空间不足：下载、解压该模型至少还需要约 " +
                (minimum / 1024 / 1024) + " MiB 可用空间"
        }
    }

    /**
     * v3.8 could resume a partially downloaded archive from another URL.
     * Prevent mixed-origin archives; Large has no pinned publisher digest.
     */
    private fun prepareSource(source: String, report: (Progress) -> Unit) {
        val key = MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val previous = partialOrigin.takeIf { it.isFile }?.readText()?.trim()
        if (partial.length() > 0 && previous != key) {
            report(Progress(0L, spec.archiveBytes,
                "检测到旧版或另一下载源的断点，安全重置以避免 ZIP 内容混合"))
            check(partial.delete()) { "旧断点清理失败，请检查文件权限" }
        }
        partialOrigin.writeText(key)
        ensureSpace()
    }

    private suspend fun fetch(source: String, report: (Progress) -> Unit) {
        val start = partial.length()
        if (start > spec.archiveBytes) partial.delete()
        val offset = partial.length()
        val conn = URL(source).openConnection() as HttpURLConnection
        conn.connectTimeout = 12_000
        conn.readTimeout = 20_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept-Encoding", "identity")
        conn.setRequestProperty("User-Agent", "OfflineRealtimeTranslator/Vosk-ASR-Manager")
        if (offset > 0) conn.setRequestProperty("Range", "bytes=" + offset + "-")
        try {
            val status = conn.responseCode
            check(conn.url.protocol.equals("https", true)) { "下载被重定向到非 HTTPS 地址" }
            check(status == 200 || status == 206) {
                "Vosk ZIP 下载源 " + conn.url.host + " 返回 HTTP " + status
            }
            val type = conn.contentType.orEmpty().lowercase(Locale.ROOT)
            check(!type.contains("text/html") && !type.contains("application/json") &&
                !type.contains("text/plain")) {
                "下载源 " + conn.url.host + " 返回网页或错误信息（" + type +
                    "），不是 Vosk ZIP，请切换官方源"
            }
            check(conn.contentEncoding == null || conn.contentEncoding.equals("identity", true)) {
                "下载源压缩了传输数据，无法保证断点文件一致"
            }
            val range = conn.getHeaderField("Content-Range")
            val append = status == 206
            val announcedBytes: Long
            if (append) {
                val m = Regex("^bytes (\\d+)-(\\d+)/(\\d+)$").matchEntire(range ?: "")
                    ?: throw IOException("无效 Content-Range")
                val rangeStart = m.groupValues[1].toLong()
                val rangeEnd = m.groupValues[2].toLong()
                val remoteSize = m.groupValues[3].toLong()
                check(remoteSize == spec.archiveBytes && rangeStart == offset &&
                    rangeEnd >= rangeStart && rangeEnd < spec.archiveBytes) {
                    "Range/ZIP 总大小不匹配：下载源声称 " + remoteSize +
                        " 字节，官方 " + spec.id + " ZIP 应为 " + spec.archiveBytes +
                        " 字节。约 3.5 GB 是解压大小，不是下载 ZIP 大小"
                }
                announcedBytes = rangeEnd - rangeStart + 1
            } else {
                // Some servers ignore Range: restart the file instead of appending.
                announcedBytes = spec.archiveBytes
            }
            if (conn.contentLengthLong >= 0) {
                check(conn.contentLengthLong == announcedBytes) {
                    "ZIP 文件不匹配：" + conn.url.host + " 返回 " + conn.contentLengthLong +
                        " 字节，本次请求应为 " + announcedBytes +
                        " 字节（官方 ZIP 总大小 " + spec.archiveBytes +
                        " 字节）。请切换官方源，勿使用网页/错误文件"
                }
            }
            conn.inputStream.use { input ->
                FileOutputStream(partial, append).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var count = if (append) offset else 0L
                    var responseCount = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        count += n
                        responseCount += n
                        check(count <= spec.archiveBytes && responseCount <= announcedBytes) {
                            "响应超出模型固定大小"
                        }
                        output.write(buffer, 0, n)
                        if (count % (1024 * 1024) < n || count == spec.archiveBytes) {
                            report(Progress(count, spec.archiveBytes, "下载 Vosk · " + conn.url.host))
                        }
                    }
                    output.fd.sync()
                    check(responseCount == announcedBytes) { "网络提前中断，可继续下载" }
                }
            }
            check(partial.length() == spec.archiveBytes) {
                "ZIP 下载未完成：已保存 " + partial.length() + "/" + spec.archiveBytes +
                    " 字节，可继续下载。3.5–3.7 GB 指解压后目录大小"
            }
        } finally { conn.disconnect() }
    }

    private suspend fun verifyArchive(file: File, report: (Progress) -> Unit): Boolean {
        if (!file.isFile || file.length() != spec.archiveBytes) return false
        val trustedHash = spec.archiveSha256 ?: return true
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(512 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
                bytes += n
                if (bytes % (16L * 1024 * 1024) < n) {
                    report(Progress(bytes, spec.archiveBytes, "校验 ZIP SHA-256"))
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == trustedHash
    }

    private suspend fun unpackAndInstall(archive: File, report: (Progress) -> Unit) {
        if (installed()) { archive.delete(); return }
        check(!previous.exists()) { "安装备份存在：未覆盖旧模型，请人工检查" }
        staging.deleteRecursively()
        check(staging.mkdirs()) { "无法创建临时解压目录" }
        try {
            val requiredRoot = spec.id + "/"
            val visited = HashSet<String>()
            var expanded = 0L
            var count = 0
            ZipFile(archive).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    currentCoroutineContext().ensureActive()
                    val entry = entries.nextElement()
                    count++
                    check(count <= 20_000) { "压缩包文件数异常" }
                    val name = entry.name
                    check(name.startsWith(requiredRoot) && !name.contains('\\') &&
                        !name.contains("..") && !name.startsWith("/") && !name.contains(':')) {
                        "ZIP 文件路径不安全或顶层目录不匹配：" + name
                    }
                    val relative = name.removePrefix(requiredRoot)
                    if (relative.isEmpty()) continue
                    val output = File(staging, relative)
                    check(output.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                        "ZIP 路径越界"
                    }
                    check(visited.add(relative)) { "ZIP 内存在重复文件：" + relative }
                    if (entry.isDirectory) {
                        check(output.isDirectory || output.mkdirs()) { "无法创建解压目录" }
                        continue
                    }
                    check(entry.size >= 0 && entry.size <= spec.maxExpandedBytes) {
                        "ZIP 条目长度异常"
                    }
                    check(output.parentFile?.isDirectory == true || output.parentFile?.mkdirs() == true) {
                        "无法建立模型文件夹"
                    }
                    val crc = CRC32()
                    var readTotal = 0L
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(output).use { sink ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                readTotal += n
                                expanded += n
                                check(readTotal <= entry.size && expanded <= spec.maxExpandedBytes) {
                                    "ZIP 解压大小超出限制"
                                }
                                crc.update(buffer, 0, n)
                                sink.write(buffer, 0, n)
                            }
                            sink.fd.sync()
                        }
                    }
                    check(readTotal == entry.size && crc.value == entry.crc) {
                        "ZIP CRC 或文件长度损坏：" + relative
                    }
                    report(Progress(expanded, spec.estimatedExpandedBytes,
                        "解压 Vosk · " + count + " 个文件"))
                }
            }
            check(isValidVoskDirectory(staging)) { "模型缺少声学文件、配置或解码图" }
            promoteStaging()
            archive.delete()
            partialOrigin.delete()
            report(Progress(spec.archiveBytes, spec.archiveBytes, "已安装 · 返回主页自动切换"))
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    /** Replace the installed model only after complete verification, with rollback. */
    private suspend fun promoteStaging() {
        currentCoroutineContext().ensureActive()
        check(!previous.exists()) { "已有安装备份，防止覆盖已有模型" }
        if (destination.exists()) {
            Files.move(destination.toPath(), previous.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
        try {
            Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (failure: Exception) {
            if (previous.exists())
                Files.move(previous.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            throw failure
        }
        previous.deleteRecursively()
        AppSettings.notifyModelsChanged()
    }

    fun isValidVoskDirectory(root: File): Boolean {
        if (!root.isDirectory) return false
        if (!File(root, "am/final.mdl").isFile) return false
        if (!File(root, "conf/mfcc.conf").isFile) return false
        return File(root, "graph").listFiles()?.any { it.isFile && it.extension == "fst" } == true
    }
}
