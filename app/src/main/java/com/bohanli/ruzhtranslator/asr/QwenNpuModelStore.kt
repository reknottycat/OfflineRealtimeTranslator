package com.bohanli.ruzhtranslator.asr

import android.content.Context
import com.bohanli.ruzhtranslator.core.ModelCatalog
import com.bohanli.ruzhtranslator.core.ModelDownloader
import java.io.File
import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Stores the verified, chip-specific LiteRT NPU model and its matching Qwen vocabulary. */
class QwenNpuModelStore private constructor(
    context: Context,
    private val modelSpec: ModelCatalog.Spec
) {
    private val app = context.applicationContext
    private val cpuModel = checkNotNull(MultilingualAsrCatalog.forId(MultilingualAsrCatalog.QWEN))
    private val cpuVocab = cpuModel.files.single { it.fileName == "vocab.json" }
    private val vocabSpec = cpuVocab.copy(engine = modelSpec.engine, directory = modelSpec.directory)
    private val model = ModelDownloader(app, modelSpec)
    private val vocab = ModelDownloader(app, vocabSpec)
    private val directory = File(app.filesDir, "models/${modelSpec.directory}")

    val modelFile = File(directory, modelSpec.fileName)
    val vocabFile = File(directory, vocabSpec.fileName)
    val bytes: Long = modelSpec.bytes + vocabSpec.bytes

    fun installed(): Boolean = model.installed() && vocab.installed()

    suspend fun download(progress: (ModelDownloader.Progress) -> Unit) = operationLock.withLock {
        if (!vocab.installed()) {
            vocab.download(cpuModel.urls(cpuVocab)) { update ->
                progress(ModelDownloader.Progress(update.received, bytes, "词表 · ${update.stage}"))
            }
        }
        if (!model.installed()) {
            val urls = listOfNotNull(
                modelSpec.modelScopeUrl(),
                modelSpec.modelScopeUrl(api = true),
                modelSpec.url("hf-mirror.com"),
                modelSpec.url("huggingface.co")
            )
            model.download(urls) { update ->
                progress(ModelDownloader.Progress(vocabSpec.bytes + update.received, bytes, update.stage))
            }
        }
        progress(ModelDownloader.Progress(bytes, bytes, "已完成"))
    }

    suspend fun remove() = operationLock.withLock {
        model.deleteModel()
        vocab.deleteModel()
    }

    companion object {
        private const val REPOSITORY = "litert-community/Qwen3-ASR-0.6B"
        private const val HF_REVISION = "5028edc410addd0f9cf0b35a58a38aeb8bea4604"
        private const val MODELSCOPE_REVISION = "2ea516d50454ad4e366acc8a16bd92d3390ef9c4"
        private val operationLock = Mutex()

        fun forChip(context: Context, soc: String): QwenNpuModelStore? {
            val chip = soc.trim().uppercase(Locale.ROOT)
            val spec = when (chip) {
                "SM8750" -> spec(chip, 1_896_782_144L,
                    "dd2f2d2debc670025f2a31afa9fca7e7536ecd50ff03fa58e53798b5cb2b2fca")
                "SM8850" -> spec(chip, 1_897_703_744L,
                    "62a9d14324b7051c9a68bf339e2b86031eb87b6d7e76fe090a992df775691267")
                else -> return null
            }
            return QwenNpuModelStore(context, spec)
        }

        private fun spec(chip: String, bytes: Long, sha256: String): ModelCatalog.Spec {
            val id = "qwen3-asr-0.6b-litert-${chip.lowercase(Locale.ROOT)}"
            return ModelCatalog.Spec(
                id, "Qwen3-ASR 0.6B NPU · $chip", id,
                "qwen3_asr_0.6b_5s_f32_Qualcomm_$chip.tflite", bytes, bytes, sha256,
                REPOSITORY, HF_REVISION, "约 1.90 GB", REPOSITORY, MODELSCOPE_REVISION
            )
        }
    }
}
