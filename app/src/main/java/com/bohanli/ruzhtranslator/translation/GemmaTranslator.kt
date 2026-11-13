package com.bohanli.ruzhtranslator.translation

import android.util.Log
import com.bohanli.ruzhtranslator.core.ModelCatalog
import com.bohanli.ruzhtranslator.settings.AppSettings
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Compatibility facade for the existing Vosk/TranslationQueue/metrics pipeline.
 * Each instance owns exactly one engine: Gemma4, LMT or HY-MT2. No silent substitution.
 * A global lease prevents simultaneous residency of translation weight sets.
 */
class GemmaTranslator(
    val engineId: String = AppSettings.translationEngine,
    private val backendPreference: String = AppSettings.inferenceBackend
) {
    companion object {
        private const val TAG = "OfflineTranslator"
        const val MODEL_FILE = ModelCatalog.GEMMA4_FILE
        private val runtimeLease = Mutex()
        private const val INSTRUCTION = "You are a professional simultaneous interpreter. Translate Russian into accurate Simplified Chinese. Preserve numbers, names, technical terms and negations. Output only the translation."
    }
    private val spec = ModelCatalog.forEngine(engineId)
    private val usesGguf = engineId != AppSettings.ENGINE_GEMMA4
    private val dispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "translator-$engineId").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val cleanupScope = CoroutineScope(SupervisorJob() + dispatcher)
    // A dispatcher alone does NOT serialize suspended Flow collection with close().
    private val operation = Mutex()
    private val closed = AtomicBoolean(false)
    private var ownsLease = false
    private var gemma: Engine? = null
    @Volatile private var lmt: LmtNativeEngine? = null
    private var path: String? = null
    private var actualBackend = AppSettings.BACKEND_CPU
    @Volatile private var ready = false
    @Volatile var onStreamToken: ((String) -> Unit)? = null
    data class TranslationResult(val text: String, val promptMs: Long, val genMs: Long,
                                 val promptTokens: Int, val genTokens: Int) {
        val totalMs: Long get() = promptMs + genMs
    }
    suspend fun initialize(modelDir: File) = withContext(dispatcher) {
        operation.withLock {
            check(!closed.get()) { "Translator closed" }
            val model = File(modelDir, spec.fileName)
            require(model.isFile && model.length() > 100_000_000L && spec.acceptsSize(model.length())) {
                "${spec.title} 模型缺失或不完整，请在设置中下载/导入 ${spec.fileName}"
            }
            runtimeLease.lock(this@GemmaTranslator)
            ownsLease = true
            try {
                check(!closed.get()) { "Translator closed" }
                path = model.absolutePath
                if (usesGguf) {
                    val runtime = LmtNativeEngine(engineId)
                    lmt = runtime
                    runtime.initialize(model)
                    actualBackend = AppSettings.BACKEND_CPU
                } else {
                    val candidates = if (backendPreference == AppSettings.BACKEND_CPU)
                        listOf(AppSettings.BACKEND_CPU)
                    else listOf(AppSettings.BACKEND_GPU, AppSettings.BACKEND_CPU)
                    var failure: Exception? = null
                    for (candidate in candidates) {
                        try { loadGemma(model.absolutePath, candidate); failure = null; break }
                        catch (e: Exception) { failure = e; Log.w(TAG, "$candidate initialization failed", e) }
                    }
                    if (gemma == null) throw IllegalStateException("Gemma CPU/GPU initialization failed", failure)
                }
                check(!closed.get()) { "Translator closed during initialization" }
                ready = true
            } catch (t: Throwable) {
                disposeOnOwnerThread()
                throw t
            }
        }
    }
    private fun loadGemma(modelPath: String, name: String) {
        val backend = if (name == AppSettings.BACKEND_GPU) Backend.GPU() else Backend.CPU()
        val instance = Engine(EngineConfig(modelPath = modelPath, backend = backend,
            cacheDir = File(modelPath).parentFile?.absolutePath))
        instance.initialize()
        gemma = instance
        actualBackend = name
    }
    fun translate(text: String): String {
        if (closed.get() || !ready) return "[${spec.title} 翻译引擎未加载]"
        return runBlocking {
            withContext(dispatcher) {
                operation.withLock {
                    if (closed.get() || !ready) return@withLock "[翻译引擎已关闭]"
                    try {
                        if (usesGguf) {
                            checkNotNull(lmt).translate(text) { chunk -> emit(chunk) }
                                .ifEmpty { "[${spec.title} 翻译结果为空]" }
                        } else generateGemma(text, true)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (first: Exception) {
                        if (engineId == AppSettings.ENGINE_GEMMA4 &&
                            actualBackend == AppSettings.BACKEND_GPU && !closed.get()) {
                            try {
                                gemma?.close()
                                gemma = null
                                loadGemma(checkNotNull(path), AppSettings.BACKEND_CPU)
                                return@withLock generateGemma(text, false)
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (second: Exception) { Log.e(TAG, "CPU fallback failed", second) }
                        }
                        Log.e(TAG, "${spec.title} inference failed", first)
                        "[${spec.title} 翻译异常: ${first.message}]"
                    }
                }
            }
        }
    }
    private fun emit(chunk: String) { if (!closed.get()) onStreamToken?.invoke(chunk) }
    private suspend fun generateGemma(input: String, streaming: Boolean): String {
        val config = ConversationConfig(systemInstruction = Contents.of(INSTRUCTION),
            samplerConfig = SamplerConfig(topK = 8, topP = 0.9, temperature = 0.1))
        val output = StringBuilder()
        checkNotNull(gemma).createConversation(config).use { conversation ->
            conversation.sendMessageAsync("Translate from Russian to Simplified Chinese:\n$input")
                .collect { message ->
                    if (closed.get()) throw CancellationException("Translator closed")
                    val chunk = message.toString()
                    output.append(chunk)
                    if (streaming) emit(chunk)
                }
        }
        return output.toString().trim().ifEmpty { "[翻译结果为空]" }
    }
    fun translateWithMetrics(text: String): TranslationResult {
        val started = System.nanoTime()
        val output = translate(text)
        return TranslationResult(output, 0L, (System.nanoTime() - started) / 1_000_000, 0, 0)
    }
    fun isAvailable(): Boolean = ready && !closed.get()
    fun destroy() {
        if (!closed.compareAndSet(false, true)) return
        ready = false
        onStreamToken = null
        lmt?.cancel()
        cleanupScope.launch {
            try { operation.withLock { disposeOnOwnerThread() } }
            finally { cleanupScope.cancel(); dispatcher.close() }
        }
    }
    private fun disposeOnOwnerThread() {
        ready = false
        try {
            try { lmt?.close() } finally { lmt = null }
            try { gemma?.close() } finally { gemma = null }
        } finally {
            if (ownsLease) { ownsLease = false; runtimeLease.unlock(this) }
        }
    }
}
