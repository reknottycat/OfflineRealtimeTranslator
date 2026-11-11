package com.bohanli.ruzhtranslator.asr

import android.content.Context
import com.bohanli.ruzhtranslator.settings.AppSettings
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Download all Android ONNX components from China-accessible sources.
 * No incompatible raw ModelScope safetensors, and no foreign direct fallback.
 * Validation is HTTPS + exact filename/length + native recognizer load,
 * NOT a cryptographic publisher SHA-256 (none is pinned for these conversions).
 */
class AsrBundleDownloader(
    context: Context, val bundle: AsrBundleCatalog.Bundle
) {
    data class Progress(val bytes: Long, val total: Long, val message: String)
    companion object { private val installationLock = Mutex() }
    private val app = context.applicationContext
    private val folder = File(app.filesDir, "models/${bundle.id}")
    private val scratch = File(app.filesDir, "asr_bundle_downloads/${bundle.id}")
    fun installed(): Boolean = bundle.files.all { part ->
        val f = File(folder, part.path)
        f.isFile && f.length() == part.bytes
    }
    fun modelDir(): File = folder
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    suspend fun download(preferred: String, customBase: String, progress: (Progress) -> Unit) =
        withContext(Dispatchers.IO) {
            installationLock.withLock {
                check(folder.isDirectory || folder.mkdirs()) { "无法创建 ASR 模型目录" }
                check(scratch.isDirectory || scratch.mkdirs()) { "无法建立断点下载目录" }
                val remaining = bundle.files.filter {
                    val f = File(folder, it.path)
                    !f.isFile || f.length() != it.bytes
                }.sumOf { it.bytes }
                check(folder.usableSpace >= remaining + 128L * 1024 * 1024) {
                    "剩余存储空间不足，模型文件还需要约 ${remaining / 1024 / 1024} MiB"
                }
                var completed = bundle.totalBytes - remaining
                for (part in bundle.files) {
                    currentCoroutineContext().ensureActive()
                    val destination = File(folder, part.path)
                    if (destination.isFile && destination.length() == part.bytes) continue
                    val urls = bundle.sourceUrls(part.path, preferred, customBase)
                    var last: Throwable? = null
                    for (url in urls) {
                        currentCoroutineContext().ensureActive()
                        try {
                            val safeName = part.path.replace('/', '_')
                            val temp = File(scratch, "$safeName.part")
                            val marker = File(scratch, "$safeName.source")
                            val expectedOrigin = hash(url)
                            val existing = if (marker.isFile) marker.readText() else ""
                            if (temp.length() > 0 && existing != expectedOrigin) {
                                check(temp.delete()) { "旧下载源断点损坏，无法重置" }
                            }
                            marker.writeText(expectedOrigin)
                            downloadOne(url, temp, part.bytes) { n ->
                                progress(Progress(completed + n, bundle.totalBytes,
                                    "下载 ${part.path} · ${URL(url).host}"))
                            }
                            check(temp.length() == part.bytes) {
                                "文件长度不正确：${part.path}，预期 ${part.bytes}，实际 ${temp.length()}"
                            }
                            check(destination.parentFile?.isDirectory == true ||
                                destination.parentFile?.mkdirs() == true) {
                                "无法建立 ${part.path} 的目标目录"
                            }
                            Files.move(temp.toPath(), destination.toPath(),
                                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                            marker.delete()
                            completed += part.bytes
                            progress(Progress(completed, bundle.totalBytes,
                                "已保存 ${part.path}（文件大小检查通过；推理格式将在加载时验证）"))
                            last = null
                            break
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (failure: Exception) {
                            last = failure
                            progress(Progress(completed, bundle.totalBytes,
                                "${URL(url).host} 下载失败：${failure.message}；尝试国内备用源"))
                        }
                    }
                    if (last != null) throw IOException(
                        "当前模型未安装完成，已保留单源断点：${last.message}", last)
                }
                check(installed()) { "模型组件仍不完整，未标记为可用" }
                AppSettings.notifyModelsChanged()
                progress(Progress(bundle.totalBytes, bundle.totalBytes,
                    "文件完整 · 仍需实际启动 sherpa-onnx 检查模型兼容性"))
            }
        }

    private suspend fun downloadOne(
        url: String, file: File, expected: Long, onProgress: (Long) -> Unit
    ) {
        require(URL(url).protocol == "https") { "禁止非 HTTPS 下载来源" }
        if (file.length() > expected) check(file.delete()) { "无法清理过长断点" }
        val offset = file.length()
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 12_000
        conn.readTimeout = 25_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept-Encoding", "identity")
        conn.setRequestProperty("User-Agent", "OfflineRealtimeTranslator/4.3-CN")
        if (offset > 0) conn.setRequestProperty("Range", "bytes=$offset-")
        try {
            val status = conn.responseCode
            check(conn.url.protocol == "https") { "下载被重定向至非 HTTPS 地址" }
            check(status == 200 || status == 206) {
                "HTTP $status（预期 200 或 206）"
            }
            val type = conn.contentType.orEmpty().lowercase()
            check(!type.contains("text/html") && !type.contains("application/json")) {
                "下载源返回了网页或错误文件，不是 ONNX 权重"
            }
            check(conn.contentEncoding == null ||
                conn.contentEncoding.equals("identity", true)) {
                "压缩传输不支持安全断点续传"
            }
            val append = status == 206
            val expectedResponse: Long
            if (append) {
                val range = conn.getHeaderField("Content-Range") ?: ""
                val match = Regex("^bytes (\\d+)-(\\d+)/(\\d+)$").matchEntire(range)
                    ?: throw IOException("Content-Range 格式错误")
                val first = match.groupValues[1].toLong()
                val last = match.groupValues[2].toLong()
                val total = match.groupValues[3].toLong()
                check(first == offset && total == expected && last == expected - 1) {
                    "Range/文件大小与目标 ONNX 不一致，已拒绝混合下载"
                }
                expectedResponse = expected - offset
            } else {
                expectedResponse = expected
            }
            if (conn.contentLengthLong >= 0)
                check(conn.contentLengthLong == expectedResponse) {
                    "下载的不是精确的 ${expectedResponse} 字节 ONNX 文件"
                }
            conn.inputStream.use { input ->
                FileOutputStream(file, append).use { output ->
                    val bytes = ByteArray(256 * 1024)
                    var received = if (append) offset else 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(bytes)
                        if (n < 0) break
                        received += n
                        check(received <= expected) { "响应超出目标权重大小" }
                        output.write(bytes, 0, n)
                        if (received % (4 * 1024 * 1024L) < n) onProgress(received)
                    }
                    output.fd.sync()
                }
            }
            check(file.length() == expected) {
                "断点已保留，当前 ${file.length()} / $expected 字节"
            }
        } finally { conn.disconnect() }
    }

    suspend fun remove() = withContext(Dispatchers.IO) {
        installationLock.withLock {
            check(folder.deleteRecursively()) { "无法删除已安装模型目录" }
            scratch.deleteRecursively()
            AppSettings.notifyModelsChanged()
        }
    }
}
