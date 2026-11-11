package com.bohanli.ruzhtranslator.asr

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.bohanli.ruzhtranslator.core.ModelCatalog
import com.bohanli.ruzhtranslator.core.ModelDownloader
import com.bohanli.ruzhtranslator.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MultilingualAsrCatalog {
    const val NEMOTRON = "sherpa-nemotron-3.5-560ms-int8"
    const val QWEN = "sherpa-qwen3-asr-0.6b-int8"

    private const val QWEN_MODELSCOPE = "zengshuishui/Qwen3-ASR-onnx"
    private const val QWEN_MODELSCOPE_REVISION = "de9e449eb376dcb472c1ce8141fbae8d524fabbd"

    class Entry internal constructor(
        val directory: String,
        val title: String,
        val sizeLabel: String,
        val files: List<ModelCatalog.Spec>,
        val tokenizerDirectory: String? = null
    ) {
        val bytes: Long = files.sumOf { it.bytes }
        val hasModelScope: Boolean get() = directory == QWEN

        fun urls(file: ModelCatalog.Spec, source: String = VoskModelCatalog.SOURCE_AUTO): List<String> {
            require(file in files) { "文件不属于 $title" }
            val isTokenizer = file.directory == tokenizerDirectory
            val hubPath = if (isTokenizer) "tokenizer/${file.fileName}" else file.fileName
            val hfPath = "${file.repository}/resolve/${file.revision}/$hubPath"
            val mirror = "https://hf-mirror.com/$hfPath"
            val official = "https://huggingface.co/$hfPath"
            val modelScope = if (hasModelScope) {
                val path = if (isTokenizer) hubPath else "model_0.6B/$hubPath"
                listOf(
                    "https://modelscope.cn/models/$QWEN_MODELSCOPE/resolve/$QWEN_MODELSCOPE_REVISION/$path",
                    "https://modelscope.cn/api/v1/models/$QWEN_MODELSCOPE/repo?Revision=$QWEN_MODELSCOPE_REVISION&FilePath=$path"
                )
            } else emptyList()

            val preferred = when (source) {
                VoskModelCatalog.SOURCE_AUTO, VoskModelCatalog.SOURCE_DOMESTIC, AppSettings.SOURCE_MODELSCOPE -> emptyList()
                VoskModelCatalog.SOURCE_OFFICIAL, AppSettings.SOURCE_HF -> listOf(official)
                AppSettings.SOURCE_HF_MIRROR -> listOf(mirror)
                else -> throw IllegalArgumentException("请选择自动、国内或官方源下载 $title 的完整模型")
            }
            return (preferred + modelScope + mirror + official).distinct()
        }

        suspend fun importDirectory(context: Context, uri: Uri, progress: (ModelDownloader.Progress) -> Unit) =
            withContext(Dispatchers.IO) {
                val root = requireNotNull(DocumentFile.fromTreeUri(context, uri)) { "无法读取所选模型文件夹" }
                val tokenizer = root.findFile("tokenizer")
                val graphDirectory = if (hasModelScope) root.findFile("model_0.6B") ?: root else root
                val documents = files.map { file ->
                    val source = if (file.directory == tokenizerDirectory) tokenizer else graphDirectory
                    val document = source?.findFile(file.fileName)
                    require(document?.isFile == true) { "缺少 ${file.fileName}，请选择已解压的 $title 文件夹" }
                    file to document.uri
                }

                var completed = 0L
                for ((file, documentUri) in documents) {
                    ModelDownloader(context, file).importFrom(documentUri) { update ->
                        progress(ModelDownloader.Progress(
                            completed + update.received, bytes, "${file.fileName} · ${update.stage}"
                        ))
                    }
                    completed += file.bytes
                }
            }
    }

    private val nemotron = Entry(
        directory = NEMOTRON,
        title = "Nemotron 3.5 ASR Streaming 0.6B",
        sizeLabel = "约 682 MB",
        files = specs(
            NEMOTRON,
            "csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11",
            "ab43d895f5985b1bbab8b6eac8607fcdc05343f3",
            Pin("encoder.int8.onnx", 657_601_403L, "012e9321373af99021415e0b0eb3ec827b4be3153be6f30d9b448fe65e896e68"),
            Pin("decoder.int8.onnx", 14_978_075L, "19f9c98fc6d0a2c33a65a43b36fdb2e914c26c0aa9764be3aebc502a1e982fb0"),
            Pin("joiner.int8.onnx", 9_504_438L, "4101c7c679a0bc30483794b27a059e34e79232aa2068d78d51231a22c8b0d7ce"),
            Pin("tokens.txt", 131_440L, "729cc103155bafa785f9cd45746cd41cabe97eab7182fc04d594129587958f8a")
        )
    )

    private val qwen = Entry(
        directory = QWEN,
        title = "Qwen3-ASR 0.6B INT8",
        sizeLabel = "约 987 MB",
        tokenizerDirectory = "$QWEN-tokenizer",
        files = specs(
            QWEN,
            "csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25",
            "68818b2313fe77bd06f6a7c5068ff3ef59d02b8a",
            Pin("conv_frontend.onnx", 44_148_281L, "d22dc4423e0940e49884e903d2ea2f7e5567c14fc1aed97e4e26d6b8f208ef9e"),
            Pin("encoder.int8.onnx", 182_491_662L, "60748d3e6744a57c9c91e1b17424a6c2990567e8adceb0783940c03ed98fa9d9"),
            Pin("decoder.int8.onnx", 755_914_231L, "4f6885be5959ae26af3089d38ee7972c5fafbeeb1cf8d5e76eab6d8b61ca5771"),
            Pin("tokenizer/merges.txt", 1_671_853L, "8831e4f1a044471340f7c0a83d7bd71306a5b867e95fd870f74d0c5308a904d5"),
            Pin("tokenizer/tokenizer_config.json", 12_487L, "4942d005604266809309cabc9f4e9cb89ce855d59b14681fdc0e1cc62ea26c4c"),
            Pin("tokenizer/vocab.json", 2_776_833L, "ca10d7e9fb3ed18575dd1e277a2579c16d108e32f27439684afa0e10b1440910")
        )
    )

    fun forId(id: String): Entry? = when (id) {
        NEMOTRON -> nemotron
        QWEN -> qwen
        else -> null
    }

    private data class Pin(val path: String, val bytes: Long, val sha256: String)

    private fun specs(id: String, repository: String, revision: String, vararg pins: Pin) = pins.map { pin ->
        val directory = if (pin.path.startsWith("tokenizer/")) "$id-tokenizer" else id
        val fileName = pin.path.substringAfterLast('/')
        ModelCatalog.Spec(id, fileName, directory, fileName, pin.bytes, pin.bytes, pin.sha256,
            repository, revision, "${pin.bytes} bytes")
    }
}
