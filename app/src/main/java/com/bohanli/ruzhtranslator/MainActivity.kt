package com.bohanli.ruzhtranslator

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
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
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bohanli.ruzhtranslator.asr.AsrAccelerationPolicy
import com.bohanli.ruzhtranslator.asr.AsrBackend
import com.bohanli.ruzhtranslator.asr.QwenNpuAsrEngine
import com.bohanli.ruzhtranslator.asr.QwenNpuModelStore
import com.bohanli.ruzhtranslator.asr.RecasepuncProcessor
import com.bohanli.ruzhtranslator.asr.MultilingualAsrCatalog
import com.bohanli.ruzhtranslator.asr.MultilingualAsrManager
import com.bohanli.ruzhtranslator.asr.NemotronQnnModelStore
import com.bohanli.ruzhtranslator.asr.QnnNemotronAsrEngine
import com.bohanli.ruzhtranslator.asr.VoskAsrManager
import com.bohanli.ruzhtranslator.asr.AsrEngine
import com.bohanli.ruzhtranslator.asr.GigaAmAsrManager
import com.bohanli.ruzhtranslator.asr.GigaAmCatalog
import com.bohanli.ruzhtranslator.core.AppStatus
import com.bohanli.ruzhtranslator.core.ModelCatalog
import com.bohanli.ruzhtranslator.core.ModelManager
import com.bohanli.ruzhtranslator.core.ModelDownloader
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
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val MODEL_RECASEPUNC = "vosk-recasepunc-ru-0.22"
        // Subtle matched source/translation colors replace the distracting
        // 16-color rainbow. Preserve visual alignment without overwhelming text.
        private val SEGMENT_COLORS = intArrayOf(
            Color.parseColor("#FFE7E8E7"),
            Color.parseColor("#FFC4D9D1"),
            Color.parseColor("#FFD1D7E0"),
            Color.parseColor("#FFE0D5C7")
        )
    }
    private lateinit var binding: ActivityMainBinding
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var modelsLoading: Job? = null
    private var voskAsr: AsrEngine? = null
    private var recasepunc: RecasepuncProcessor? = null
    private var gemmaTranslator: GemmaTranslator? = null
    private var translationQueue: TranslationQueue? = null
    @Volatile private var isListening = false
    @Volatile private var isPaused = false
    private var lastPartial = ""
    private var currentAsrModel = AppSettings.ASR_MODEL_SMALL
    private var currentAsrLanguage = "auto"
    @Volatile private var currentAsrBackend: String? = null
    private val multilingualAsr get() = MultilingualAsrCatalog.forId(currentAsrModel)
    private val needsFinalDrain get() = multilingualAsr != null || currentAsrModel == AppSettings.ASR_MODEL_GIGAAM
    private val sourceLabel get() = when (if (multilingualAsr == null) "ru" else currentAsrLanguage) {
        "ru" -> "俄语"
        "zh" -> "中文"
        "en" -> "英语"
        else -> "自动语种"
    }
    private var currentTranslationEngine = AppSettings.ENGINE_GEMMA4
    private var currentInferenceBackend = AppSettings.BACKEND_AUTO
    private var currentModelGeneration = 0
    private var settingsOpened = false
    private var lastModelLoadError: String? = null
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
        currentAsrLanguage = AppSettings.asrLanguage
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
        if (multilingualAsr != null) {
            binding.tvAsrBrand.text = "ASR"
            binding.tvAsrDirection.text = "离线运行 · ${sourceLabel}输入"
            binding.tvAsrTranscriptTitle.text = "语音原文"
            binding.tvRussianHistory.hint = "说话后，识别结果会实时显示在这里"
        }
        // Keep Start interactive even on a clean install without model weights.
        binding.btnStartStop.isEnabled = true
        binding.btnStartStop.alpha = 0.9f
        binding.btnStartStop.setOnClickListener {
            if (isListening || isPaused) stopListening() else requestMic()
        }
        // The bottom composer text is part of the same primary start/stop action.
        binding.tvRecordAction.setOnClickListener { binding.btnStartStop.performClick() }
        binding.tvRecordCaption.setOnClickListener { binding.btnStartStop.performClick() }
        binding.btnPauseResume.setOnClickListener { if (isPaused) resumeFromPause() else pauseListening() }
        binding.btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java).putExtra("tab", 0))
        }
        binding.btnFavorites.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java).putExtra("tab", 1))
        }
        binding.btnSettings.setOnClickListener {
            if (needsFinalDrain &&
                voskAsr?.hasPendingResults() == true) {
                updateStatus(AppStatus.Loading("正在完成最后一段识别，请稍后进入设置"))
                return@setOnClickListener
            }
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
        binding.tvFollowRu.text = if (enabled) "● 自动跟随" else "回到最新"
        binding.tvFollowRu.setTextColor(Color.parseColor(
            if (enabled) "#FFB9D7CC" else "#FFF0F0F0"))
        binding.btnScrollBottomRu.visibility = if (enabled) View.GONE else View.VISIBLE
    }
    private fun setChineseFollow(enabled: Boolean) {
        autoScrollChinese = enabled
        binding.tvFollowZh.text = if (enabled) "● 自动跟随" else "回到最新"
        binding.tvFollowZh.setTextColor(Color.parseColor(
            if (enabled) "#FFB9D7CC" else "#FFF0F0F0"))
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
            val sv = v as ScrollView
            val child = sv.getChildAt(0) ?: return@setOnScrollChangeListener
            val nextFollow = LiveFollowPolicy.nextState(
                userDragging = userDragging, currentY = y, previousY = previousY,
                viewportHeight = sv.height, contentHeight = child.height,
                bottomSlack = (20 * resources.displayMetrics.density).toInt()
            )
            if (nextFollow != null) changeFollow(nextFollow)
        }
    }

    private fun loadModels() {
        val spec = ModelCatalog.forEngine(currentTranslationEngine)
        val entry = multilingualAsr
        val canTranslate = entry == null || currentAsrLanguage == "ru" ||
            currentTranslationEngine == AppSettings.ENGINE_HYMT2
        val translator = GemmaTranslator(currentTranslationEngine, currentInferenceBackend)
        gemmaTranslator = translator
        modelsLoading = mainScope.launch {
            var startupAsr: AsrEngine? = null
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
                        if (entry != null) {
                            val instance = loadMultilingualAsr(entry)
                            startupAsr = instance
                            progress()
                            return@async instance
                        }
                        val instance: AsrEngine = if (currentAsrModel == AppSettings.ASR_MODEL_GIGAAM) {
                            val dir = ModelManager.getModelDir(this@MainActivity, GigaAmCatalog.MODEL_ID)
                                ?: error("GigaAM v2 俄语模型未安装，请在设置中点选第三套 ASR 模型并下载")
                            check(ModelDownloader(this@MainActivity, GigaAmCatalog.SPEC).installed()) {
                                "GigaAM v2 模型未通过 SHA-256 身份验证，请重新下载或导入官方 ONNX 文件"
                            }
                            GigaAmAsrManager(this@MainActivity, dir,
                                { onVoskPartial(it) }, { onVoskFinal(it) },
                                { updateStatus(AppStatus.Error(it)) },
                                { refreshStoppedState() })
                        } else {
                            val dir = ModelManager.getModelDir(this@MainActivity, currentAsrModel)
                                ?: error("Vosk 俄语模型未安装（$currentAsrModel），请在设置中选择 Small/Large 并下载")
                            VoskAsrManager(dir.absolutePath,
                                { onVoskPartial(it) }, { onVoskFinal(it) },
                                { updateStatus(AppStatus.Error(it)) })
                        }
                        startupAsr = instance
                        instance.initialize()
                        progress()
                        instance
                    }
                    val punctuation = async(Dispatchers.IO) {
                        if (entry != null) {
                            progress()
                            return@async null
                        }
                        val dir = ModelManager.getModelDir(this@MainActivity, MODEL_RECASEPUNC)
                        val instance = RecasepuncProcessor(dir)
                        startupPunctuation = instance
                        progress()
                        instance
                    }
                    val translation = async(Dispatchers.IO) {
                        if (!canTranslate) {
                            progress()
                            return@async false
                        }
                        val dir = ModelManager.getModelDir(this@MainActivity, spec.directory)
                        if (dir == null) {
                            Log.i(TAG, "Translation model not installed: ${spec.title}; ASR-only mode")
                            false
                        } else try {
                            translator.initialize(dir)
                            progress()
                            translator.isAvailable()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            Log.e(TAG, "Translation initialization failed; ASR-only remains available", failure)
                            false
                        }
                    }
                    val readyAsr = asr.await()
                    val readyPunctuation = punctuation.await()
                    val translatorReady = translation.await()
                    Triple(readyAsr, readyPunctuation, translatorReady)
                }
                voskAsr = initialized.first
                recasepunc = initialized.second
                if (initialized.third) {
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
                } else {
                    // A side-by-side preview has no inherited 2.6 GB translation weights.
                    // Do not discard an otherwise fully usable GigaAM/Vosk recognizer.
                    translator.destroy()
                    gemmaTranslator = null
                    binding.tvAutosaveStatus.text =
                        if (!canTranslate) "仅转写 · 多语输入可选择 HY-MT2 翻译"
                        else "仅转写 · 字幕自动保存，安装翻译模型后可开启中文翻译"
                }
                complete = true
                lastModelLoadError = null
                binding.btnStartStop.isEnabled = true
                binding.btnStartStop.alpha = 0.9f
                updateStatus(if (HomeStartPolicy.mode(voskAsr != null, initialized.third) ==
                    HomeStartPolicy.Mode.FULL_TRANSLATION) AppStatus.Ready
                    else AppStatus.AsrOnly)
                // Opening the app or changing an engine must not automatically turn the microphone on.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Model loading failed", e)
                lastModelLoadError = e.message ?: "语音识别模型加载失败"
                updateStatus(AppStatus.Error("$lastModelLoadError · 点击开始按钮配置模型"))
            } finally {
                binding.btnStartStop.isEnabled = true
                binding.btnStartStop.alpha = 0.9f
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

    private suspend fun loadMultilingualAsr(
        entry: MultilingualAsrCatalog.Entry,
        tryNpu: Boolean = true
    ): AsrEngine {
        val error: (String) -> Unit = { message ->
            runOnUiThread {
                if (!isDestroyed && !settingsOpened) {
                    if (isListening || isPaused) stopListening()
                    val failedNpu = voskAsr as? QnnNemotronAsrEngine
                    if (failedNpu != null && !failedNpu.isUsable) {
                        failedNpu.release()
                        voskAsr = null
                        currentAsrBackend = null
                        reloadAsrOnCpu(entry, message)
                    } else {
                        updateStatus(AppStatus.Error(message))
                    }
                }
            }
        }
        val chipset = QnnNemotronAsrEngine.chipset()
        val qnnStore = if (tryNpu && entry.directory == MultilingualAsrCatalog.NEMOTRON &&
            QnnNemotronAsrEngine.isRuntimePresent(this)) {
            NemotronQnnModelStore.forCurrentDevice(this)?.takeIf { it.installed() }
        } else null
        val qwenStore = if (tryNpu && entry.directory == MultilingualAsrCatalog.QWEN &&
            chipset != null && QwenNpuAsrEngine.isRuntimePresent(this)) {
            QwenNpuModelStore.forChip(this, chipset)?.takeIf { it.installed() }
        } else null
        val cpuReady = entry.files.all { ModelDownloader(this, it).installed() }
        val cpuDirectory = File(filesDir, "models/${entry.directory}")
        val backend = AsrAccelerationPolicy.backend(
            entry.directory, chipset, tryNpu, qnnStore != null || qwenStore != null, cpuReady
        )
        if (backend == AsrBackend.NPU) {
            val accelerator = if (qnnStore != null) {
                QnnNemotronAsrEngine(this, qnnStore.directory, currentAsrLanguage,
                    ::onVoskPartial, ::onAsrFinal, error, ::refreshStoppedState)
            } else {
                val store = checkNotNull(qwenStore)
                MultilingualAsrManager(this, cpuDirectory, entry.directory, currentAsrLanguage,
                    ::onVoskPartial, ::onAsrFinal, error, ::refreshStoppedState,
                    qwenNpu = QwenNpuAsrEngine(this, store.modelFile, store.vocabFile),
                    cpuQwenAvailable = cpuReady,
                    onCpuFallback = {
                        currentAsrBackend = "CPU"
                        updateStatus(if (isListening) AppStatus.Listening else AppStatus.Loading("正在完成最后一段识别"))
                    })
            }
            try {
                accelerator.initialize()
                currentAsrBackend = "骁龙 NPU"
                return accelerator
            } catch (failure: Exception) {
                accelerator.release()
                if (failure is CancellationException) throw failure
                Log.w(TAG, "ASR NPU initialization failed for ${entry.directory}", failure)
                check(cpuReady) { "骁龙 NPU 启动失败，请在设置下载 CPU 备用模型" }
            }
        }
        check(cpuReady) { "${entry.title} 尚未安装，请在设置中下载模型" }
        val cpu = MultilingualAsrManager(this, cpuDirectory, entry.directory, currentAsrLanguage,
            ::onVoskPartial, ::onAsrFinal, error, ::refreshStoppedState)
        try {
            cpu.initialize()
            currentAsrBackend = "CPU"
            return cpu
        } catch (failure: Exception) {
            cpu.release()
            throw failure
        }
    }

    private fun reloadAsrOnCpu(entry: MultilingualAsrCatalog.Entry, message: String) {
        updateStatus(AppStatus.Loading("NPU 连接中断，正在加载 CPU 备用模型"))
        modelsLoading = mainScope.launch {
            var cpu: AsrEngine? = null
            try {
                withContext(Dispatchers.IO) { cpu = loadMultilingualAsr(entry, tryNpu = false) }
                voskAsr = cpu
                cpu = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w(TAG, "ASR CPU fallback unavailable", failure)
                lastModelLoadError = "$message · 可在设置下载 CPU 备用模型"
            } finally {
                cpu?.release()
                refreshStoppedState()
            }
        }
    }

    private fun onVoskPartial(partial: String) {
        if (settingsOpened || isDestroyed || !isListening || isPaused) return
        lastPartial = partial
        updateRussianDisplay(partial)
    }
    private fun onVoskFinal(rawText: String) = onAsrFinal(rawText, "ru")

    private fun onAsrFinal(rawText: String, language: String) {
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
                    if (queue != null) {
                        if (language == "zh" || language.startsWith("zh-")) {
                            // Chinese is already in the target language. Use the same queue
                            // to preserve ordering relative to segments still translating.
                            queue.submit(processed, alreadyTargetLanguage = true)
                        } else {
                            queue.submit(processed)
                        }
                    }
                }
            } finally {
                pendingAsrResults = (pendingAsrResults - 1).coerceAtLeast(0)
                refreshStoppedState()
            }
        }
    }
    private fun requestMic() {
        if (modelsLoading?.isActive == true) {
            updateStatus(AppStatus.Loading("正在初始化语音识别模型，请完成后再点击开始"))
            return
        }
        if (HomeStartPolicy.mode(voskAsr != null,
                gemmaTranslator?.isAvailable() == true) == HomeStartPolicy.Mode.NEED_ASR) {
            val name = multilingualAsr?.title ?: when (currentAsrModel) {
                AppSettings.ASR_MODEL_GIGAAM -> "GigaAM v2 Russian INT8（236 MB ONNX）"
                AppSettings.ASR_MODEL_LARGE -> "Vosk Large 0.42"
                else -> "Vosk Small 0.22"
            }
            AlertDialog.Builder(this)
                .setTitle("请安装或修复语音识别模型")
                .setMessage("当前选择：" + name + "\n\n" +
                    (lastModelLoadError ?: "所选 ASR 模型尚未就绪") + "\n\n" +
                    "独立测试包可以在设置下载或导入模型。只测试转写无需安装翻译模型。")
                .setNegativeButton("取消", null)
                .setPositiveButton("打开模型设置") { _, _ ->
                    binding.btnSettings.performClick()
                }.show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED) startListening()
        else requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    private fun startListening() {
        if (settingsOpened || modelsLoading?.isActive == true) return
        val asr = voskAsr ?: run { updateStatus(AppStatus.Error("语音识别引擎未就绪")); return }
        // Starting a new recording must not discard translations still finishing.
        if (pendingAsrResults > 0 || translationQueue?.hasPending() == true ||
            voskAsr?.hasPendingResults() == true) {
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
        if (lastPartial.isNotBlank() && !needsFinalDrain) {
            val text = lastPartial; lastPartial = ""
            appendRussianSegment(text); translationQueue?.submit(text)
        } else if (needsFinalDrain) {
            // These recognizers can emit a final utterance after Stop.
            // Never persist speculative partial text as a second completed sentence.
            lastPartial = ""
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
        if (lastPartial.isNotBlank() && !needsFinalDrain) {
            val text = lastPartial; lastPartial = ""
            appendRussianSegment(text); translationQueue?.submit(text)
            updateRussianDisplay()
        } else if (needsFinalDrain) {
            lastPartial = ""
            updateRussianDisplay()
        }
        saveSubtitleSnapshot()
        translationQueue?.pause()
        updateStatus(AppStatus.Paused)
        binding.btnPauseResume.setImageResource(R.drawable.ic_play_arrow)
        binding.btnPauseResume.setBackgroundResource(R.drawable.bg_circle_button_start)
    }
    private fun resumeFromPause() {
        if (voskAsr == null) {
            updateStatus(AppStatus.Error("语音识别模型未就绪，请进入设置重新检查"))
            return
        }
        if (needsFinalDrain &&
            voskAsr?.hasPendingResults() == true) {
            updateStatus(AppStatus.Loading("正在保存暂停前最后一句，完成后再继续"))
            return
        }
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
        val pending = pendingAsrResults > 0 || translationQueue?.hasPending() == true ||
            voskAsr?.hasPendingResults() == true
        // Never hide remediation behind an untappable disabled Start button.
        binding.btnStartStop.isEnabled = true
        binding.btnStartStop.alpha = 0.9f
        updateStatus(if (pending) AppStatus.Loading("正在完成最后一段识别或翻译，自动保存中")
        else when (HomeStartPolicy.mode(voskAsr != null,
            gemmaTranslator?.isAvailable() == true)) {
            HomeStartPolicy.Mode.NEED_ASR ->
                AppStatus.Error(lastModelLoadError ?: "所选 ASR 尚未就绪，点开始进入设置")
            HomeStartPolicy.Mode.ASR_ONLY -> AppStatus.AsrOnly
            HomeStartPolicy.Mode.FULL_TRANSLATION -> AppStatus.Ready
        })
    }

    private fun updateStatus(status: AppStatus) {
        if (!isDestroyed && !settingsOpened) {
            val entry = multilingualAsr
            val engineTitle = if (entry != null) {
                val translation = if (gemmaTranslator?.isAvailable() == true) {
                    val label = if (currentTranslationEngine == AppSettings.ENGINE_HYMT2) "HY-MT2 · CPU"
                        else ModelCatalog.forEngine(currentTranslationEngine).title
                    " → $label"
                } else ""
                val asrTitle = if (entry.directory == MultilingualAsrCatalog.QWEN &&
                    currentAsrBackend == "骁龙 NPU") "Qwen3-ASR 0.6B" else entry.title
                "$asrTitle${currentAsrBackend?.let { " · $it" }.orEmpty()}$translation"
            } else if (gemmaTranslator?.isAvailable() == true)
                ModelCatalog.forEngine(currentTranslationEngine).title
            else when (currentAsrModel) {
                AppSettings.ASR_MODEL_GIGAAM -> "GigaAM v2 俄语识别"
                AppSettings.ASR_MODEL_LARGE -> "Vosk Large 俄语识别"
                else -> "Vosk Small 俄语识别"
            }
            binding.tvStatus.text = engineTitle + " · " + status.toDisplayString()
            binding.tvStatus.setTextColor(Color.parseColor(
                if (status is AppStatus.Error) "#FFE8A6A2" else "#FFDFE2E0"))
            updateComposerDock(status)
        }
    }

    /** An always-visible primary action mirrors the recorder state. */
    private fun updateComposerDock(status: AppStatus) {
        val busy = isListening || isPaused
        binding.tvRecordAction.text = when {
            isPaused -> "已暂停"
            isListening -> "正在聆听"
            status is AppStatus.Loading -> "正在准备模型"
            status is AppStatus.Error -> "设置语音识别"
            status == AppStatus.AsrOnly -> "开始转写"
            else -> "开始同声传译"
        }
        binding.tvRecordCaption.text = when {
            isPaused -> "点击右侧继续 · 已识别字幕自动保存"
            isListening -> "点击左侧按钮停止并保存字幕"
            status is AppStatus.Loading -> "模型加载中，暂不可录音"
            status is AppStatus.Error -> "点击左侧按钮查看缺失模型"
            status == AppStatus.AsrOnly -> "${sourceLabel}原文 · 自动保存到历史"
            else -> "${sourceLabel}识别 + 中文翻译 · 全部本地处理"
        }
        binding.btnStartStop.imageTintList = ColorStateList.valueOf(
            Color.parseColor(if (busy) "#FFFFFFFF" else "#FF10201B"))
        binding.btnStartStop.contentDescription =
            if (busy) "停止监听并保存字幕" else "开始监听"
        binding.btnPauseResume.contentDescription =
            if (isPaused) "继续监听" else "暂停监听"
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
