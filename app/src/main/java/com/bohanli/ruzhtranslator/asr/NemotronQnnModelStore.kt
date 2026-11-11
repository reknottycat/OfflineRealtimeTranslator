package com.bohanli.ruzhtranslator.asr

import android.content.Context
import android.os.Build
import com.bohanli.ruzhtranslator.core.ModelCatalog
import com.bohanli.ruzhtranslator.core.ModelDownloadTransport
import com.bohanli.ruzhtranslator.core.ModelDownloader
import com.bohanli.ruzhtranslator.settings.AppSettings
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/** Installs the publisher's SoC-specific QNN context files in app-private storage. */
class NemotronQnnModelStore private constructor(
    context: Context,
    val socModel: String,
    private val spec: ModelCatalog.Spec
) {
    private val app = context.applicationContext
    val directory = File(app.filesDir, "models/${spec.directory}")
    private val staging = File(directory.parentFile, ".${spec.directory}.installing")
    private val work = File(app.filesDir, "nemotron_qnn_downloads/${spec.directory}")
    private val archive = File(work, "${spec.fileName}.part")

    fun installed(): Boolean = runCatching {
        File(directory, RECEIPT_NAME).readText() == receiptText(directory)
    }.getOrDefault(false)

    suspend fun download(progress: (ModelDownloader.Progress) -> Unit) = withContext(Dispatchers.IO) {
        installLock.withLock {
            if (installed()) return@withLock
            check(!staging.exists() || staging.deleteRecursively()) { "无法清理上次解压的临时目录" }
            check(staging.mkdirs()) { "无法创建 Nemotron 模型目录" }
            try {
                val official = "https://github.com/${spec.repository}/releases/download/$RELEASE_TAG/${spec.fileName}"
                // These mirrors returned binary ranges for the pinned release assets.
                val sources = PROXY_PREFIXES.map { it + official }
                ModelDownloadTransport(spec, archive).download(sources) {
                    progress(ModelDownloader.Progress(it.received, it.expected, it.stage))
                }
                extractVerifiedArchive(progress)
                FileOutputStream(File(staging, RECEIPT_NAME)).use { output ->
                    output.write(receiptText(staging).toByteArray(Charsets.UTF_8))
                    output.fd.sync()
                }
                currentCoroutineContext().ensureActive()
                check(!directory.exists() || directory.deleteRecursively()) { "无法替换不完整的 Nemotron 模型" }
                Files.move(staging.toPath(), directory.toPath(), StandardCopyOption.ATOMIC_MOVE)
                AppSettings.notifyModelsChanged()
                work.deleteRecursively()
                progress(ModelDownloader.Progress(spec.bytes, spec.bytes, "Nemotron QNN 模型已验证安装 · $socModel"))
            } finally {
                staging.deleteRecursively()
            }
        }
    }

    suspend fun remove() = withContext(Dispatchers.IO) {
        installLock.withLock {
            for (target in listOf(directory, staging, work)) {
                check(!target.exists() || target.deleteRecursively()) { "无法删除 ${target.name}" }
            }
            AppSettings.notifyModelsChanged()
        }
    }

    private fun receiptText(at: File): String = buildString {
        appendLine(spec.sha256)
        for (name in REQUIRED_FILES) {
            val file = File(at, name)
            check(file.isFile && file.length() > 0) { "Nemotron QNN 模型缺少 $name" }
            appendLine("$name:${file.length()}:${file.lastModified()}")
        }
    }

    private suspend fun extractVerifiedArchive(progress: (ModelDownloader.Progress) -> Unit) {
        val seen = mutableSetOf<String>()
        val folder = spec.fileName.removeSuffix(".tar.bz2")
        var entries = 0
        var declaredBytes = 0L
        val buffer = ByteArray(256 * 1024)
        archive.inputStream().buffered().use { input ->
            TarArchiveInputStream(BZip2CompressorInputStream(input)).use { tar ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = tar.nextEntry ?: break
                    check(++entries <= MAX_ENTRIES && entry.size in 0L..(MAX_EXPANDED_BYTES - declaredBytes)) {
                        "Nemotron 压缩包内容超过解压上限"
                    }
                    declaredBytes += entry.size
                    val path = entry.name.removePrefix("./")
                    val name = path.substringAfterLast('/')
                    if (name !in REQUIRED_FILES) continue
                    check((path == "$folder/$name" || path == name) && entry.isFile && !entry.isSparse &&
                        !entry.isLink && !entry.isSymbolicLink && entry.size > 0 && seen.add(name)) {
                        "Nemotron 压缩包中的 $name 不符合发布结构"
                    }
                    check(staging.usableSpace >= entry.size + STORAGE_MARGIN_BYTES) { "存储空间不足，已保留下载进度" }
                    progress(ModelDownloader.Progress(spec.bytes, spec.bytes, "解压 Nemotron QNN · $name"))
                    FileOutputStream(File(staging, name)).use { output ->
                        var remaining = entry.size
                        while (remaining > 0) {
                            currentCoroutineContext().ensureActive()
                            val count = tar.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                            check(count > 0) { "Nemotron 压缩包中的 $name 不完整" }
                            output.write(buffer, 0, count)
                            remaining -= count
                        }
                        output.fd.sync()
                    }
                }
            }
        }
        check(seen == REQUIRED_FILES) { "Nemotron 压缩包缺少必要文件：${(REQUIRED_FILES - seen).joinToString()}" }
    }

    companion object {
        private const val RELEASE_TAG = "asr-models-qnn-binary-3"
        private const val RELEASE_COMMIT = "f8ffa43055466c7bdf84bbc83f7feb053d200f28"
        private const val RECEIPT_NAME = ".verified"
        private const val MAX_ENTRIES = 64
        private const val MAX_EXPANDED_BYTES = 1024L * 1024 * 1024
        private const val STORAGE_MARGIN_BYTES = 32L * 1024 * 1024
        private val REQUIRED_FILES = setOf("encoder.bin", "decoder.bin", "joiner.bin", "tokens.txt")
        private val PROXY_PREFIXES = listOf("https://gh-proxy.org/", "https://ghproxy.imciel.com/")
        private val installLock = Mutex()

        private val specs = mapOf(
            "SM8750" to spec("SM8750", 442_651_414L, "a5af6d03ebba0425074e38d0ebd819fff88ff404fd7340433de30bb515dbbd51"),
            "SM8850" to spec("SM8850", 442_760_175L, "feb72d6576e2e61b1755abc794026093c978b47bfb20516c494452569a423b31")
        )

        fun forCurrentDevice(context: Context): NemotronQnnModelStore? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || "arm64-v8a" !in Build.SUPPORTED_64_BIT_ABIS) return null
            val soc = Build.SOC_MODEL.trim().uppercase(Locale.ROOT)
            return specs[soc]?.let { NemotronQnnModelStore(context, soc, it) }
        }

        private fun spec(soc: String, bytes: Long, sha256: String) = ModelCatalog.Spec(
            engine = "nemotron-qnn",
            title = "Nemotron 3.5 ASR QNN · $soc",
            directory = "nemotron-qnn-560ms-${soc.lowercase(Locale.ROOT)}",
            fileName = "sherpa-onnx-qnn-$soc-binary-nemotron-3.5-asr-streaming-0.6b-560ms.tar.bz2",
            bytes = bytes,
            maximumBytes = bytes,
            sha256 = sha256,
            repository = "k2-fsa/sherpa-onnx",
            revision = RELEASE_COMMIT,
            sizeLabel = "约 443 MB"
        )
    }
}
