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
import android.view.MotionEvent
import android.widget.ScrollView
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
import com.bohanli.ruzhtranslator.history.SubtitleAutosaveWriter
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
    private var pendingAsrResults = 0
    private var subtitleSaver: SubtitleAutosaveWriter? = null
    private var russianScrollPosted = false
    private var chineseScrollPosted = false
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
        saveSubtitleSnapshot()
        subtitleSaver?.close()
        subtitleSaver = null
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
            if (lastPartial.isNotBlank()) {
                appendRussianSegment(lastPartial)
                lastPartial = ""
                updateRussianDisplay()
            }
            saveSessionToHistory() // closes journal and clears live buffers before recreation
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
            setRussianFollow(true)
            scrollRussianToLatest()
        }
        binding.btnScrollBottomZh.setOnClickListener {
            setChineseFollow(true)
            scrollChineseToLatest()
        }
        binding.tvFollowRu.setOnClickListener {
            setRussianFollow(!autoScrollRussian)
            if (autoScrollRussian) scrollRussianToLatest()
        }
        binding.tvFollowZh.setOnClickListener {
            setChineseFollow(!autoScrollChinese)
            if (autoScrollChinese) scrollChineseToLatest()
        }
        setupSmartScroll(binding.scrollViewRussian, ::setRussianFollow)
        setupSmartScroll(binding.scrollViewChinese, ::setChineseFollow)
        setRussianFollow(true)
        setChineseFollow(true)
        binding.tvAutosaveStatus.text = "自动保存已开启 · 识别完成即存入历史"
    }

    private fun setRussianFollow(enabled: Boolean) {
        autoScrollRussian = enabled
        binding.tvFollowRu.text = if (enabled) "● 自动跟随" else "跟随已暂停 · 点击恢复"
        binding.btnScrollBottomRu.visibility = if (enabled) View.GONE else View.VISIBLE
    }
    private fun setChineseFollow(enabled: Boolean) {
        autoScrollChinese = enabled
        binding.tvFollowZh.text = if (enabled) "● 自动跟随" else "跟随已暂停 · 点击恢复"
        binding.btnScrollBottomZh.visibility = if (enabled) View.GONE else View.VISIBLE
    }
    private fun scrollRussianToLatest() {
        if (russianScrollPosted) return
        russianScrollPosted = true
        binding.scrollViewRussian.post {
            russianScrollPosted = false
            if (autoScrollRussian) binding.scrollViewRussian.fullScroll(View.FOCUS_DOWN)
        }
    }
    private fun scrollChineseToLatest() {
        if (chineseScrollPosted) return
        chineseScrollPosted = true
        binding.scrollViewChinese.post {
            chineseScrollPosted = false
            if (autoScrollChinese) binding.scrollViewChinese.fullScroll(View.FOCUS_DOWN)
        }
    }

    /** Only a genuine user drag may pause follow; layout/stream updates must not. */
    private fun setupSmartScroll(scrollView: ScrollView, changeFollow: (Boolean) -> Unit) {
        var userDragging = false
        scrollView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> userDragging = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> userDragging = false
            }
            false // keep native scrolling, text selection, and accessibility
        }
        scrollView.setOnScrollChangeListener { v, _, y, _, previousY ->
            if (!userDragging || y == previousY) return@setOnScrollChangeListener
            val sv = v as ScrollView
            val child = sv.getChildAt(0) ?: return@setOnScrollChangeListener
            val atBottom = y + sv.height >= child.height - (20 * resources.displayMetrics.density)
            changeFollow(atBottom)
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
                        else refreshStoppedState()
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
        if (settingsOpened || isDestroyed || !isListening || isPaused) return
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
        pendingAsrResults++
        mainScope.launch {
            try {
                val processed = withContext(Dispatchers.IO) {
                    rcp?.takeIf { it.isAvailable() }?.process(rawText) ?: rawText
                }
                if (epoch == sessionEpoch && !settingsOpened && processed.isNotBlank()) {
                    appendRussianSegment(processed) // Auto-save now, not when Activity is destroyed.
                    updateRussianDisplay()
                    queue?.submit(processed)
                }
            } finally {
                pendingAsrResults = (pendingAsrResults - 1).coerceAtLeast(0)
                refreshStoppedState()
            }
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
        // Starting a new recording must not discard translations still finishing.
        if (pendingAsrResults > 0 || translationQueue?.hasPending() == true) {
            updateStatus(AppStatus.Loading("请等待上一段翻译完成，字幕正自动保存"))
            return
        }
        saveSessionToHistory()
        sessionEpoch++
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
        saveSubtitleSnapshot()
        refreshStoppedState()
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
        saveSubtitleSnapshot()
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
        // End the previous session; accepted snapshots drain in order on the IO worker.
        saveSubtitleSnapshot()
        subtitleSaver?.close()
        subtitleSaver = null
        lastPartial = ""
        streamingText.clear()
        russianSegments.clear()
        chineseSegments.clear()
        binding.tvRussianHistory.text = ""
        binding.tvChineseHistory.text = ""
        binding.tvAutosaveStatus.text = "自动保存已开启 · 新一段字幕"
        setRussianFollow(true)
        setChineseFollow(true)
    }

    /** Save ASR finals even if translation has not yet produced a Chinese line. */
    private fun saveSubtitleSnapshot() {
        if (russianSegments.isEmpty() && chineseSegments.isEmpty()) return
        val writer = subtitleSaver ?: SubtitleAutosaveWriter(
            db.translationDao(),
            onSaved = {
                if (!isDestroyed && !isFinishing) binding.tvAutosaveStatus.text = "✓ 字幕已自动保存至历史"
            },
            onFailure = {
                if (!isDestroyed && !isFinishing)
                    binding.tvAutosaveStatus.text = "保存失败：请检查设备存储空间"
            }
        ).also { subtitleSaver = it }
        if (writer.capture(russianSegments.joinToString("\n"), chineseSegments.joinToString("\n"))) {
            binding.tvAutosaveStatus.text = "正在自动保存字幕..."
        }
    }

    private fun refreshStoppedState() {
        if (settingsOpened || isListening || isPaused || isDestroyed) return
        val pending = pendingAsrResults > 0 || translationQueue?.hasPending() == true
        val ready = !pending && gemmaTranslator?.isAvailable() == true
        binding.btnStartStop.isEnabled = ready
        binding.btnStartStop.alpha = if (ready) 0.75f else 0.4f
        updateStatus(if (pending) AppStatus.Loading("正在收尾翻译，完成后自动保存") else AppStatus.Ready)
    }

    private fun updateStatus(status: AppStatus) {
        if (!isDestroyed && !settingsOpened) binding.tvStatus.text =
            "${ModelCatalog.forEngine(currentTranslationEngine).title} · ${status.toDisplayString()}"
    }
    private fun appendRussianSegment(text: String) {
        russianSegments.add(text)
        saveSubtitleSnapshot()
    }
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
        scrollRussianToLatest()
    }
    private fun startChineseStream() { streamingText.clear() }
    private fun appendStreamToken(token: String) { streamingText.append(token); updateChineseDisplay(streamingText.toString()) }
    private fun finalizeChinese(text: String) {
        streamingText.clear()
        chineseSegments.add(text)
        saveSubtitleSnapshot()
        updateChineseDisplay(null)
    }
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
        scrollChineseToLatest()
    }
}
