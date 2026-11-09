package com.bohanli.ruzhtranslator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bohanli.ruzhtranslator.asr.RecasepuncProcessor
import com.bohanli.ruzhtranslator.asr.VoskAsrManager
import com.bohanli.ruzhtranslator.core.AppStatus
import com.bohanli.ruzhtranslator.core.ModelCatalog
import com.bohanli.ruzhtranslator.core.ModelManager
import com.bohanli.ruzhtranslator.databinding.ActivityMainBinding
import com.bohanli.ruzhtranslator.history.AppDatabase
import com.bohanli.ruzhtranslator.history.HistoryActivity
import com.bohanli.ruzhtranslator.history.TranslationRecord
import com.bohanli.ruzhtranslator.settings.AppSettings
import com.bohanli.ruzhtranslator.settings.SettingsActivity
import com.bohanli.ruzhtranslator.translation.GemmaTranslator
import com.bohanli.ruzhtranslator.translation.TranslationQueue
import com.bohanli.ruzhtranslator.translation.TranslatorHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val MODEL_RECASEPUNC = "vosk-recasepunc-ru-0.22"
        private val SEGMENT_COLORS = intArrayOf(
            Color.parseColor("#FFFF6666"), Color.parseColor("#FFFF8855"),
            Color.parseColor("#FFFF9944"), Color.parseColor("#FFFFBB44"),
            Color.parseColor("#FFFFDD55"), Color.parseColor("#FFCCEE55"),
            Color.parseColor("#FF80FF80"), Color.parseColor("#FF44FFBB"),
            Color.parseColor("#FF44FFDD"), Color.parseColor("#FF44DDFF"),
            Color.parseColor("#FF66BBFF"), Color.parseColor("#FF8899FF"),
            Color.parseColor("#FFAA88FF"), Color.parseColor("#FFCC88FF"),
            Color.parseColor("#FFFF80CC"), Color.parseColor("#FFFF80A0")
        )
    }
    private lateinit var binding: ActivityMainBinding
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var modelsLoading: Job? = null
    private var voskAsr: VoskAsrManager? = null
    private var recasepunc: RecasepuncProcessor? = null
    private var gemmaTranslator: GemmaTranslator? = null
    private var translationQueue: TranslationQueue? = null
    @Volatile private var isListening = false
    @Volatile private var isPaused = false
    private var lastPartial = ""
    private var currentAsrModel = AppSettings.ASR_MODEL_SMALL
    private var currentTranslationEngine = AppSettings.ENGINE_GEMMA4
    private var currentInferenceBackend = AppSettings.BACKEND_AUTO
    private var currentModelGeneration = 0
    private var settingsOpened = false
    private var sessionEpoch = 0
    private val russianSegments = mutableListOf<String>()
    private val chineseSegments = mutableListOf<String>()
    private var autoScrollRussian = true
    private var autoScrollChinese = true
    private var streamingText = StringBuilder()
    private val db by lazy { AppDatabase.getInstance(this) }
    private val requestMicPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startListening() else updateStatus(AppStatus.Error(getString(R.string.permission_denied)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.init(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        currentAsrModel = AppSettings.asrModel
        currentTranslationEngine = AppSettings.translationEngine
        currentInferenceBackend = AppSettings.inferenceBackend
        currentModelGeneration = AppSettings.modelGeneration
        setupUi()
        loadModels()
    }

    override fun onResume() {
        super.onResume()
        // Apply all settings at a clean session boundary, even when the old model was missing.
        if (settingsOpened || AppSettings.translationEngine != currentTranslationEngine ||
            AppSettings.inferenceBackend != currentInferenceBackend ||
            AppSettings.modelGeneration != currentModelGeneration || AppSettings.asrModel != currentAsrModel) {
            settingsOpened = false
            recreate()
        }
    }

    override fun onDestroy() {
        sessionEpoch++
        modelsLoading?.cancel()
        saveSessionToHistorySync()
        isListening = false
        isPaused = false
        TranslatorHolder.set(null)
        translationQueue?.clear()
        translationQueue?.shutdown()
        gemmaTranslator?.destroy()
        voskAsr?.release()
        recasepunc?.close()
        mainScope.cancel()
        super.onDestroy()
    }

    private fun setupUi() {
        binding.btnStartStop.isEnabled = false
        binding.btnStartStop.alpha = 0.4f
        binding.btnStartStop.setOnClickListener {
            if (isListening || isPaused) stopListening() else requestMic()
        }
        binding.btnPauseResume.setOnClickListener { if (isPaused) resumeFromPause() else pauseListening() }
        binding.btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java).putExtra("tab", 0))
        }
        binding.btnFavorites.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java).putExtra("tab", 1))
        }
        binding.btnSettings.setOnClickListener {
            // No active recorder, queue or translator may keep running while model files are managed.
            settingsOpened = true
            sessionEpoch++
            modelsLoading?.cancel()
            isListening = false
            isPaused = false
            voskAsr?.stopListening()
            translationQueue?.clear()
            translationQueue?.shutdown()
            translationQueue = null
            TranslatorHolder.set(null)
            gemmaTranslator?.destroy()
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnScrollBottomRu.setOnClickListener {
            binding.scrollViewRussian.fullScroll(View.FOCUS_DOWN)
            autoScrollRussian = true
            binding.btnScrollBottomRu.visibility = View.GONE
        }
        binding.btnScrollBottomZh.setOnClickListener {
            binding.scrollViewChinese.fullScroll(View.FOCUS_DOWN)
            autoScrollChinese = true
            binding.btnScrollBottomZh.visibility = View.GONE
        }
        setupSmartScroll(binding.scrollViewRussian, { autoScrollRussian }, { autoScrollRussian = it }, binding.btnScrollBottomRu)
        setupSmartScroll(binding.scrollViewChinese, { autoScrollChinese }, { autoScrollChinese = it }, binding.btnScrollBottomZh)
    }

    private fun setupSmartScroll(scrollView: android.widget.ScrollView, getAutoScroll: () -> Boolean,
                                 setAutoScroll: (Boolean) -> Unit, scrollBtn: View) {
        scrollView.setOnScrollChangeListener { v, _, scrollY, _, _ ->
            val sv = v as android.widget.ScrollView
            val child = sv.getChildAt(0) ?: return@setOnScrollChangeListener
            if (scrollY + sv.height >= child.height - 50) {
                setAutoScroll(true); scrollBtn.visibility = View.GONE
            } else if (getAutoScroll()) {
                setAutoScroll(false); scrollBtn.visibility = View.VISIBLE
            }
        }
    }

    private fun loadModels() {
        val spec = ModelCatalog.forEngine(currentTranslationEngine)
        val translator = GemmaTranslator(currentTranslationEngine, currentInferenceBackend)
        gemmaTranslator = translator
        modelsLoading = mainScope.launch {
            var startupAsr: VoskAsrManager? = null
            var startupPunctuation: RecasepuncProcessor? = null
            var complete = false
            try {
                updateStatus(AppStatus.Loading("加载 ${spec.title}..."))
                val loadedCount = AtomicInteger(0)
                suspend fun progress() {
                    val count = loadedCount.incrementAndGet()
                    withContext(Dispatchers.Main) { updateStatus(AppStatus.Loading("加载 ${spec.title} ($count/3)...")) }
                }
                val initialized = coroutineScope {
                    val asr = async(Dispatchers.IO) {
                        val dir = ModelManager.getModelDir(this@MainActivity, currentAsrModel)
                            ?: error("Vosk 俄语模型尚未安装（$currentAsrModel）。请进入设置 → 语音识别模型，选择 Small/Large 并点击下载；也可放至：" +
                                "${ModelManager.getExternalModelDir(this@MainActivity)}/$currentAsrModel/")
                        val instance = VoskAsrManager(dir.absolutePath,
                            { onVoskPartial(it) }, { onVoskFinal(it) }, { updateStatus(AppStatus.Error(it)) })
                        startupAsr = instance
                        instance.initialize()
                        progress()
                        instance
                    }
                    val punctuation = async(Dispatchers.IO) {
                        val dir = ModelManager.getModelDir(this@MainActivity, MODEL_RECASEPUNC)
                        val instance = RecasepuncProcessor(dir)
                        startupPunctuation = instance
                        progress()
                        instance
                    }
                    val translation = async(Dispatchers.IO) {
                        val dir = ModelManager.getModelDir(this@MainActivity, spec.directory)
                            ?: error("${spec.title} 模型未安装，打开设置下载或导入 ${spec.fileName}")
                        translator.initialize(dir)
                        progress()
                    }
                    val readyAsr = asr.await()
                    val readyPunctuation = punctuation.await()
                    translation.await()
                    readyAsr to readyPunctuation
                }
                voskAsr = initialized.first
                recasepunc = initialized.second
                TranslatorHolder.set(translator)
                translationQueue = TranslationQueue(translator,
                    onResult = { finalizeChinese(it) },
                    onStreamToken = { appendStreamToken(it) },
                    onStreamStart = { startChineseStream() },
                    onBusyChanged = { busy ->
                        if (busy) updateStatus(AppStatus.Translating)
                        else if (isPaused) updateStatus(AppStatus.Paused)
                        else if (isListening) updateStatus(AppStatus.Listening)
                        else updateStatus(AppStatus.Ready)
                    })
                complete = true
                binding.btnStartStop.isEnabled = translator.isAvailable()
                binding.btnStartStop.alpha = if (translator.isAvailable()) 0.75f else 0.4f
                updateStatus(if (translator.isAvailable()) AppStatus.Ready else AppStatus.Error("翻译引擎未就绪"))
                // Opening the app or changing an engine must not automatically turn the microphone on.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Model loading failed", e)
                updateStatus(AppStatus.Error(e.message ?: "模型加载失败"))
            } finally {
                if (!complete) {
                    translator.destroy()
                    startupAsr?.release()
                    startupPunctuation?.close()
                    if (voskAsr === startupAsr) voskAsr = null
                    if (recasepunc === startupPunctuation) recasepunc = null
                }
            }
        }
    }

    private fun onVoskPartial(partial: String) {
        if (settingsOpened || isDestroyed) return
        lastPartial = partial
        updateRussianDisplay(partial)
    }
    private fun onVoskFinal(rawText: String) {
        if (settingsOpened || isDestroyed) return
        lastPartial = ""
        if (rawText.isBlank()) { updateRussianDisplay(); return }
        val rcp = recasepunc
        val queue = translationQueue
        val epoch = sessionEpoch
        mainScope.launch {
            val processed = withContext(Dispatchers.IO) {
                rcp?.takeIf { it.isAvailable() }?.process(rawText) ?: rawText
            }
            if (epoch != sessionEpoch || settingsOpened) return@launch
            if (processed.isNotBlank()) { appendRussianSegment(processed); queue?.submit(processed) }
            updateRussianDisplay()
        }
    }
    private fun requestMic() {
        if (gemmaTranslator?.isAvailable() != true) {
            updateStatus(AppStatus.Error("请先安装并加载所选翻译模型")); return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            startListening()
        else requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    private fun startListening() {
        if (settingsOpened || gemmaTranslator?.isAvailable() != true) return
        val asr = voskAsr ?: run { updateStatus(AppStatus.Error("语音识别引擎未就绪")); return }
        sessionEpoch++
        saveSessionToHistory()
        isListening = true; isPaused = false
        asr.startListening()
        updateStatus(AppStatus.Listening)
        binding.btnStartStop.setImageResource(R.drawable.ic_stop_square)
        binding.btnStartStop.setBackgroundResource(R.drawable.bg_circle_button_stop)
        binding.btnPauseResume.visibility = View.VISIBLE
        binding.btnPauseResume.setImageResource(R.drawable.ic_pause)
        binding.btnPauseResume.setBackgroundResource(R.drawable.bg_circle_button_pause)
    }
    private fun stopListening() {
        isListening = false; isPaused = false
        voskAsr?.stopListening()
        translationQueue?.resume()
        if (lastPartial.isNotBlank()) {
            val text = lastPartial; lastPartial = ""
            appendRussianSegment(text); translationQueue?.submit(text)
        }
        updateRussianDisplay()
        updateStatus(AppStatus.Ready)
        binding.btnStartStop.setImageResource(R.drawable.ic_play_arrow)
        binding.btnStartStop.setBackgroundResource(R.drawable.bg_circle_button_start)
        binding.btnPauseResume.visibility = View.GONE
    }
    private fun pauseListening() {
        isPaused = true
        voskAsr?.stopListening()
        if (lastPartial.isNotBlank()) {
            val text = lastPartial; lastPartial = ""
            appendRussianSegment(text); translationQueue?.submit(text)
            updateRussianDisplay()
        }
        translationQueue?.pause()
        updateStatus(AppStatus.Paused)
        binding.btnPauseResume.setImageResource(R.drawable.ic_play_arrow)
        binding.btnPauseResume.setBackgroundResource(R.drawable.bg_circle_button_start)
    }
    private fun resumeFromPause() {
        if (gemmaTranslator?.isAvailable() != true) return
        isPaused = false
        translationQueue?.resume()
        voskAsr?.startListening()
        isListening = true
        updateStatus(AppStatus.Listening)
        binding.btnPauseResume.setImageResource(R.drawable.ic_pause)
        binding.btnPauseResume.setBackgroundResource(R.drawable.bg_circle_button_pause)
    }
    private fun saveSessionToHistory() {
        if (russianSegments.isEmpty() && chineseSegments.isEmpty()) return
        translationQueue?.clear()
        streamingText.clear()
        if (russianSegments.isNotEmpty() && chineseSegments.isNotEmpty()) {
            val ruText = russianSegments.joinToString("\n")
            val zhText = chineseSegments.joinToString("\n")
            mainScope.launch { withContext(Dispatchers.IO) {
                db.translationDao().insert(TranslationRecord(ruText = ruText, zhText = zhText))
            } }
        }
        russianSegments.clear(); chineseSegments.clear()
        binding.tvRussianHistory.text = ""; binding.tvChineseHistory.text = ""
    }
    private fun saveSessionToHistorySync() {
        if (russianSegments.isEmpty() || chineseSegments.isEmpty()) return
        val ruText = russianSegments.joinToString("\n")
        val zhText = chineseSegments.joinToString("\n")
        try {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                db.translationDao().insert(TranslationRecord(ruText = ruText, zhText = zhText))
            }
        } catch (e: Exception) { Log.e(TAG, "Failed to save session", e) }
        russianSegments.clear(); chineseSegments.clear()
    }
    private fun updateStatus(status: AppStatus) {
        if (!isDestroyed && !settingsOpened) binding.tvStatus.text =
            "${ModelCatalog.forEngine(currentTranslationEngine).title} · ${status.toDisplayString()}"
    }
    private fun appendRussianSegment(text: String) { russianSegments.add(text) }
    private fun updateRussianDisplay(partial: String = "") {
        val ssb = SpannableStringBuilder()
        for ((i, segment) in russianSegments.withIndex()) {
            if (ssb.isNotEmpty()) ssb.append("\n")
            val start = ssb.length; ssb.append(segment)
            ssb.setSpan(ForegroundColorSpan(SEGMENT_COLORS[i % SEGMENT_COLORS.size]), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (partial.isNotEmpty()) {
            if (ssb.isNotEmpty()) ssb.append("\n")
            val start = ssb.length; ssb.append(partial)
            ssb.setSpan(ForegroundColorSpan(Color.parseColor("#FF888888")), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        binding.tvRussianHistory.text = ssb
        if (autoScrollRussian) binding.scrollViewRussian.post { binding.scrollViewRussian.fullScroll(View.FOCUS_DOWN) }
    }
    private fun startChineseStream() { streamingText.clear() }
    private fun appendStreamToken(token: String) { streamingText.append(token); updateChineseDisplay(streamingText.toString()) }
    private fun finalizeChinese(text: String) { streamingText.clear(); chineseSegments.add(text); updateChineseDisplay(null) }
    private fun updateChineseDisplay(streaming: String?) {
        val ssb = SpannableStringBuilder()
        for ((i, segment) in chineseSegments.withIndex()) {
            if (ssb.isNotEmpty()) ssb.append("\n")
            val start = ssb.length; ssb.append(segment)
            ssb.setSpan(ForegroundColorSpan(SEGMENT_COLORS[i % SEGMENT_COLORS.size]), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (!streaming.isNullOrEmpty()) {
            if (ssb.isNotEmpty()) ssb.append("\n")
            val start = ssb.length; ssb.append(streaming)
            ssb.setSpan(ForegroundColorSpan(SEGMENT_COLORS[chineseSegments.size % SEGMENT_COLORS.size]), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        binding.tvChineseHistory.text = ssb
        if (autoScrollChinese) binding.scrollViewChinese.post { binding.scrollViewChinese.fullScroll(View.FOCUS_DOWN) }
    }
}
