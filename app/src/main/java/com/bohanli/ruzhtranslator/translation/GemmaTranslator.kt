package com.bohanli.ruzhtranslator.translation

import android.util.Log
import com.bohanli.ruzhtranslator.settings.AppSettings
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * LiteRT-LM translation engine for Gemma 4 E2B.
 *
 * The old public class name is retained so the Vosk/Recasepunc/TranslationQueue pipeline
 * does not need an invasive rewrite. All LiteRT-LM calls run on one dedicated thread.
 * Model files are provisioned by ModelManager and never packaged in the APK.
 */
class GemmaTranslator {
    companion object {
        private const val TAG = "Gemma4LiteRT"
        const val MODEL_FILE = "gemma-4-E2B-it.litertlm"
        private const val INSTRUCTION =
            "You are a professional simultaneous interpreter. Translate each Russian " +
            "utterance into natural, accurate Simplified Chinese. Preserve numbers, " +
            "technical terms, names, and negations exactly. Output only Chinese " +
            "translation, with no explanations, labels, or markdown."
    }

    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "gemma4-litertlm").apply { isDaemon = true }
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    private var engine: Engine? = null
    private var modelPath: String? = null
    private var activeBackend: String = AppSettings.BACKEND_CPU
    @Volatile private var ready = false
    @Volatile private var destroyed = false
    @Volatile var onStreamToken: ((String) -> Unit)? = null

    data class TranslationResult(
        val text: String,
        val promptMs: Long,
        val genMs: Long,
        val promptTokens: Int,
        val genTokens: Int,
    ) {
        val totalMs: Long get() = promptMs + genMs
    }

    suspend fun initialize(modelDir: File) = withContext(dispatcher) {
        check(!destroyed) { "Translator already destroyed" }
        val model = modelDir.listFiles()?.firstOrNull {
            it.isFile && it.name == MODEL_FILE && it.length() > 100_000_000L
        }
        if (model == null) {
            Log.w(TAG, "Model missing or incomplete in ${modelDir.absolutePath}")
            return@withContext
        }
        modelPath = model.absolutePath
        loadEngine(model.absolutePath)
    }

    private fun loadEngine(path: String) {
        // Called only on the dedicated inference dispatcher.
        ready = false
        engine?.close()
        engine = null
        val candidates = when (AppSettings.inferenceBackend) {
            AppSettings.BACKEND_CPU -> listOf(AppSettings.BACKEND_CPU)
            else -> listOf(AppSettings.BACKEND_GPU, AppSettings.BACKEND_CPU)
        }
        var lastError: Throwable? = null
        for (name in candidates) {
            try {
                val backend = if (name == AppSettings.BACKEND_GPU) Backend.GPU()
                    else Backend.CPU()
                val instance = Engine(EngineConfig(
                    modelPath = path,
                    backend = backend,
                    cacheDir = File(path).parentFile?.absolutePath
                ))
                // Do NOT close an Engine on which initialize() threw: LiteRT-LM forbids it.
                instance.initialize()
                engine = instance
                activeBackend = name
                ready = true
                Log.i(TAG, "Gemma 4 E2B ready with backend=$name")
                return
            } catch (t: Throwable) {
                lastError = t
                Log.w(TAG, "LiteRT-LM backend $name failed: ${t.message}")
            }
        }
        throw IllegalStateException("CPU and GPU initialization failed", lastError)
    }

    fun translate(text: String): String = runBlocking {
        withContext(dispatcher) {
            if (!ready || engine == null) return@withContext "[翻译引擎未加载：请先下载 Gemma 4 E2B 模型]"
            try {
                generate(text)
            } catch (first: Exception) {
                Log.e(TAG, "Translate failed on $activeBackend", first)
                // Some GPUs fail at first inference even after successfully initializing.
                if (activeBackend == AppSettings.BACKEND_GPU) {
                    try {
                        ready = false
                        engine?.close()
                        engine = null
                        val path = checkNotNull(modelPath)
                        val cpu = Engine(EngineConfig(modelPath = path, backend = Backend.CPU()))
                        cpu.initialize()
                        engine = cpu
                        activeBackend = AppSettings.BACKEND_CPU
                        ready = true
                        onStreamToken = null // Do not double-stream a partially failed result.
                        return@withContext generate(text)
                    } catch (second: Exception) {
                        Log.e(TAG, "CPU fallback failed", second)
                    }
                }
                "[翻译异常: ${first.message}]"
            }
        }
    }

    private suspend fun generate(input: String): String {
        val current = engine ?: error("Engine not initialized")
        val config = ConversationConfig(
            systemInstruction = Contents.of(INSTRUCTION),
            samplerConfig = SamplerConfig(topK = 8, topP = 0.9, temperature = 0.1)
        )
        val output = StringBuilder()
        // Independent conversation per segment avoids unbounded context growth and
        // cross-segment instruction leakage. Engine stays loaded and reused.
        current.createConversation(config).use { conversation ->
            conversation.sendMessageAsync(
                "Translate from Russian to Simplified Chinese:\n$input"
            ).collect { message ->
                val chunk = message.toString()
                output.append(chunk)
                onStreamToken?.invoke(chunk)
            }
        }
        return output.toString().trim().ifEmpty { "[翻译结果为空]" }
    }

    fun translateWithMetrics(text: String): TranslationResult {
        val started = System.nanoTime()
        val result = translate(text)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        // LiteRT-LM does not expose the llama.cpp prompt/token counters here.
        return TranslationResult(result, 0L, elapsedMs, 0, 0)
    }

    fun isAvailable(): Boolean = ready && !destroyed

    fun destroy() {
        if (destroyed) return
        destroyed = true
        runBlocking {
            withContext(dispatcher) {
                ready = false
                engine?.close()
                engine = null
            }
        }
        dispatcher.close()
    }
}
