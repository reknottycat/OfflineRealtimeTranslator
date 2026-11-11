package com.bohanli.ruzhtranslator.asr

import android.content.Context
import android.os.Build
import com.bohanli.ruzhtranslator.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Provides the LiteRT dispatcher and the matching QAIRT libraries for the Qwen worker. */
object QwenNpuRuntime {
    private const val ASSET_DIRECTORY = "qwen-npu-runtime"
    private const val CACHE_DIRECTORY = "qwen-litert-2.3.0-qairt-2.50.0"
    private val extractionLock = Any()

    fun isPresent(context: Context): Boolean {
        val version = htpVersion() ?: return false
        return runCatching {
            val names = context.assets.list(ASSET_DIRECTORY).orEmpty().toSet()
            requiredFiles(version).all(names::contains)
        }.getOrDefault(false)
    }

    /** Call on an I/O thread in the Qwen service before creating its LiteRT environment. */
    fun prepare(context: Context): File = synchronized(extractionLock) {
        val version = checkNotNull(htpVersion()) { "此设备没有可用的 Qwen NPU 运行库" }
        val files = requiredFiles(version)
        val cache = File(context.codeCacheDir, CACHE_DIRECTORY)
        val target = File(cache, "htp-$version")
        if (files.all { File(target, it).length() > 0L }) return@synchronized target

        val staging = File(cache, ".htp-$version.installing")
        check(!staging.exists() || staging.deleteRecursively()) { "无法清理 Qwen NPU 运行库临时目录" }
        check(staging.mkdirs()) { "无法创建 Qwen NPU 运行库目录" }
        try {
            for (name in files) {
                val file = File(staging, name)
                context.assets.open("$ASSET_DIRECTORY/$name").use { input ->
                    FileOutputStream(file).use { output ->
                        check(file.setReadOnly()) { "无法锁定 Qwen NPU 运行库 $name" }
                        check(input.copyTo(output, bufferSize = 256 * 1024) > 0L) { "Qwen NPU 运行库 $name 为空" }
                        output.fd.sync()
                    }
                }
            }
            check(!target.exists() || target.deleteRecursively()) { "无法替换不完整的 Qwen NPU 运行库" }
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            target
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun htpVersion(): String? {
        if (!BuildConfig.QNN_ENABLED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            "arm64-v8a" !in Build.SUPPORTED_64_BIT_ABIS) return null
        return when (QnnNemotronAsrEngine.chipset()) {
            "SM8750" -> "79"
            "SM8850" -> "81"
            else -> null
        }
    }

    private fun requiredFiles(version: String) = listOf(
        "libLiteRtDispatch_Qualcomm.so",
        "libQnnHtp.so",
        "libQnnSystem.so",
        "libQnnHtpV${version}Stub.so",
        "libQnnHtpV${version}Skel.so",
    )
}
