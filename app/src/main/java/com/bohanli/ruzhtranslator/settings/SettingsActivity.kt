package com.bohanli.ruzhtranslator.settings

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.RadioGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatRadioButton
import com.bohanli.ruzhtranslator.R
import com.bohanli.ruzhtranslator.asr.VoskModelCatalog
import com.bohanli.ruzhtranslator.asr.GigaAmCatalog
import com.bohanli.ruzhtranslator.asr.VoskArchiveInstaller
import com.bohanli.ruzhtranslator.core.ModelManager
import com.bohanli.ruzhtranslator.core.ModelCatalog
import com.bohanli.ruzhtranslator.core.ModelDownloader
import com.bohanli.ruzhtranslator.databinding.ActivitySettingsBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var hyMtButton: AppCompatRadioButton
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val sourceKeys = listOf(AppSettings.SOURCE_AUTO, AppSettings.SOURCE_MODELSCOPE,
        AppSettings.SOURCE_HF_MIRROR, AppSettings.SOURCE_CN_PROXY, AppSettings.SOURCE_HF, AppSettings.SOURCE_CUSTOM)
    private val asrSourceKeys = listOf(VoskModelCatalog.SOURCE_AUTO, VoskModelCatalog.SOURCE_DOMESTIC,
        VoskModelCatalog.SOURCE_OFFICIAL, VoskModelCatalog.SOURCE_CUSTOM)
    private var renderedAsr = AppSettings.ASR_MODEL_SMALL
    private var asrDownloading = false
    private var pendingImportAsr = AppSettings.ASR_MODEL_SMALL
    private val customUrls by lazy { getSharedPreferences("model_custom_urls", MODE_PRIVATE) }
    private var rendering = false
    private var busy = false
    private var downloading = false
    private var operationJob: Job? = null
    private var renderedEngine = AppSettings.ENGINE_GEMMA4
    private var pendingImportEngine = AppSettings.ENGINE_GEMMA4
    private fun selectedModel() = ModelCatalog.forEngine(AppSettings.translationEngine)
    private val importModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val installer = ModelDownloader(this, ModelCatalog.forEngine(pendingImportEngine))
            startModelOperation { installer.importFrom(uri, ::showProgress) }
        }
    }
    private val importAsrArchive = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            if (pendingImportAsr == AppSettings.ASR_MODEL_GIGAAM) {
                startAsrOperation {
                    ModelDownloader(this, GigaAmCatalog.SPEC).importFrom(uri) { p ->
                        showAsrProgress(VoskArchiveInstaller.Progress(p.received, p.expected, p.stage))
                    }
                }
            } else {
                val installer = VoskArchiveInstaller(this, VoskModelCatalog.model(pendingImportAsr))
                startAsrOperation { installer.importZip(uri, ::showAsrProgress) }
            }
        }
    }
    private val importAsrFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            val selected = VoskModelCatalog.model(pendingImportAsr)
            startAsrOperation {
                VoskArchiveInstaller(this, selected).importDirectory(uri, ::showAsrProgress)
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.init(this)
        pendingImportEngine = savedInstanceState?.getString("import_engine") ?: AppSettings.translationEngine
        pendingImportAsr = savedInstanceState?.getString("import_asr") ?: AppSettings.asrModel
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Extend the existing settings card; no replacement of the speech/subtitle UI.
        hyMtButton = AppCompatRadioButton(this).apply {
            id = R.id.rb_hymt2
            setText(R.string.engine_hymt2)
            setTextColor(binding.rbLmt.textColors)
            buttonTintList = binding.rbLmt.buttonTintList
            textSize = binding.rbLmt.textSize / resources.displayMetrics.scaledDensity
            minHeight = (50 * resources.displayMetrics.density).toInt()
            setPadding((12 * resources.displayMetrics.density).toInt(), 0,
                (12 * resources.displayMetrics.density).toInt(), 0)
            setBackgroundResource(R.drawable.ui_option_row)
        }
        binding.radioTranslationEngine.addView(hyMtButton,
            RadioGroup.LayoutParams(RadioGroup.LayoutParams.MATCH_PARENT,
                RadioGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (7 * resources.displayMetrics.density).toInt()
            })
        binding.btnBack.setOnClickListener { finish() }
        binding.tvVersion.text = "v4.2-alpha · 界面重构 · GigaAM / Vosk / 三翻译引擎"
        binding.radioAsrModel.check(when (AppSettings.asrModel) {
            AppSettings.ASR_MODEL_GIGAAM -> binding.rbAsrGigaam.id
            AppSettings.ASR_MODEL_LARGE -> binding.rbAsrLarge.id
            else -> binding.rbAsrSmall.id
        })
        binding.radioAsrModel.setOnCheckedChangeListener { _, id ->
            if (busy) return@setOnCheckedChangeListener
            customUrls.edit().putString("vosk:" + renderedAsr, binding.etAsrUrl.text.toString()).apply()
            AppSettings.asrModel = when (id) {
                binding.rbAsrGigaam.id -> AppSettings.ASR_MODEL_GIGAAM
                binding.rbAsrLarge.id -> AppSettings.ASR_MODEL_LARGE
                else -> AppSettings.ASR_MODEL_SMALL
            }
            refreshAsrUi()
        }
        binding.spinnerAsrSource.adapter = ArrayAdapter(this, R.layout.spinner_item,
            arrayOf("自动 · 国内 HF Mirror 优先（Small/GigaAM）", "国内镜像优先（Large 只使用官方）",
                "模型官方源优先", "自定义 HTTPS ZIP / ONNX 直链"))
            .also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }
        binding.spinnerAsrSource.setSelection(asrSourceKeys.indexOf(AppSettings.asrSource).coerceAtLeast(0))
        binding.spinnerAsrSource.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                AppSettings.asrSource = asrSourceKeys[position]
                refreshAsrSource()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        binding.btnDownloadAsr.setOnClickListener {
            if (AppSettings.asrModel == AppSettings.ASR_MODEL_GIGAAM) {
                val url = binding.etAsrUrl.text.toString()
                val urls = try { GigaAmCatalog.urls(AppSettings.asrSource, url) }
                    catch (e: IllegalArgumentException) {
                        toast(e.message ?: "请检查 GigaAM 下载源")
                        return@setOnClickListener
                    }
                customUrls.edit().putString("vosk:" + GigaAmCatalog.MODEL_ID, url).apply()
                startAsrOperation(isDownload = true) {
                    ModelDownloader(this, GigaAmCatalog.SPEC).download(urls) { p ->
                        showAsrProgress(VoskArchiveInstaller.Progress(p.received, p.expected, p.stage))
                    }
                }
                return@setOnClickListener
            }
            val spec = VoskModelCatalog.model(AppSettings.asrModel)
            val url = binding.etAsrUrl.text.toString()
            val urls = try { VoskModelCatalog.urls(spec, AppSettings.asrSource, url) }
                catch (e: IllegalArgumentException) { toast(e.message ?: "请检查 Vosk 下载源"); return@setOnClickListener }
            customUrls.edit().putString("vosk:" + spec.id, url).apply()
            val beginDownload = {
                startAsrOperation(isDownload = true) {
                    VoskArchiveInstaller(this, spec).download(urls, ::showAsrProgress)
                }
            }
            if (spec.isLarge) {
                AlertDialog.Builder(this)
                    .setTitle("确认下载服务器级 Vosk Large？")
                    .setMessage("官方压缩包约 1.84 GB，解压后约 3.5–3.7 GB；运行时可能需要高达 16 GB 内存。手机可能卡顿或加载失败。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("下载官方 ZIP") { _, _ -> beginDownload() }
                    .show()
            } else beginDownload()
        }
        binding.btnPauseAsr.setOnClickListener { operationJob?.cancel() }
        binding.btnImportAsr.setOnClickListener {
            pendingImportAsr = AppSettings.asrModel
            importAsrArchive.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }
        binding.btnImportAsrFolder.setOnClickListener {
            pendingImportAsr = AppSettings.asrModel
            importAsrFolder.launch(null)
        }
        binding.btnRemoveAsr.setOnClickListener {
            if (AppSettings.asrModel == AppSettings.ASR_MODEL_GIGAAM) {
                AlertDialog.Builder(this).setTitle("删除 GigaAM v2 模型？")
                    .setMessage("只删除本应用 GigaAM 的 ONNX 权重，不影响 Vosk 及翻译历史。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除") { _, _ ->
                        startAsrOperation { ModelDownloader(this, GigaAmCatalog.SPEC).deleteModel() }
                    }.show()
                return@setOnClickListener
            }
            val spec = VoskModelCatalog.model(AppSettings.asrModel)
            AlertDialog.Builder(this).setTitle("删除 " + spec.title + "？")
                .setMessage("仅删除应用内下载的该 Vosk 模型和断点，不删除外部手动导入目录或翻译历史。")
                .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                    startAsrOperation { VoskArchiveInstaller(this, spec).remove() }
                }.show()
        }
        binding.radioTranslationEngine.setOnCheckedChangeListener { _, id ->
            if (!rendering && !busy) {
                customUrls.edit().putString(renderedEngine, binding.etModelUrl.text.toString()).apply()
                AppSettings.translationEngine = when (id) {
                    binding.rbLmt.id -> AppSettings.ENGINE_LMT
                    hyMtButton.id -> AppSettings.ENGINE_HYMT2
                    else -> AppSettings.ENGINE_GEMMA4
                }
                refreshModelUi()
            }
        }
        binding.radioInferenceBackend.setOnCheckedChangeListener { _, id ->
            if (!rendering && !busy && AppSettings.translationEngine == AppSettings.ENGINE_GEMMA4) {
                AppSettings.inferenceBackend = when (id) {
                    binding.rbBackendCpu.id -> AppSettings.BACKEND_CPU
                    binding.rbBackendGpu.id -> AppSettings.BACKEND_GPU
                    else -> AppSettings.BACKEND_AUTO
                }
            }
        }
        binding.switchDriftRetry.isChecked = AppSettings.driftRetryEnabled
        binding.switchDriftRetry.setOnCheckedChangeListener { _, checked -> AppSettings.driftRetryEnabled = checked }
        binding.spinnerModelSource.adapter = ArrayAdapter(this, R.layout.spinner_item,
            arrayOf("自动（国内源优先，失败换源）", "魔搭 ModelScope 优先", "HF Mirror 优先",
                "备用代理优先（自动轮换）", "Hugging Face 优先", "自定义 HTTPS 优先"))
            .also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }
        // Preserve a v3.5 user-entered URL: ModelScope and custom are now distinct choices.
        if (!customUrls.getBoolean("source_selection_migrated_v1", false)) {
            val hasOldCustom = ModelCatalog.all.any { customUrls.getString(it.engine, "").orEmpty().isNotBlank() }
            if (AppSettings.modelSource == AppSettings.SOURCE_MODELSCOPE && hasOldCustom) {
                AppSettings.modelSource = AppSettings.SOURCE_CUSTOM
            }
            customUrls.edit().putBoolean("source_selection_migrated_v1", true).apply()
        }
        binding.spinnerModelSource.setSelection(sourceKeys.indexOf(AppSettings.modelSource).coerceAtLeast(0))
        binding.spinnerModelSource.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                AppSettings.modelSource = sourceKeys[position]
                refreshSourceUi()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        binding.btnDownloadModel.setOnClickListener {
            val spec = selectedModel()
            val installer = ModelDownloader(this, spec)
            val urls = try {
                ModelCatalog.downloadUrls(AppSettings.modelSource, spec.engine, binding.etModelUrl.text.toString())
            } catch (e: IllegalArgumentException) {
                toast(e.message ?: "请检查下载来源"); return@setOnClickListener
            }
            customUrls.edit().putString(spec.engine, binding.etModelUrl.text.toString()).apply()
            startModelOperation(isDownload = true) { installer.download(urls, ::showProgress) }
        }
        binding.btnPauseModel.setOnClickListener { operationJob?.cancel() }
        binding.btnImportModel.setOnClickListener {
            pendingImportEngine = AppSettings.translationEngine
            importModel.launch(arrayOf("*/*"))
        }
        binding.btnRemoveModel.setOnClickListener {
            val spec = selectedModel()
            AlertDialog.Builder(this).setTitle("删除 ${spec.title}？")
                .setMessage("仅删除此引擎的本地权重和断点文件，不影响其他模型或翻译历史。")
                .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                    startModelOperation { ModelDownloader(this, spec).deleteModel() }
                }.show()
        }
        refreshModelUi()
        refreshAsrUi()
    }

    private fun refreshAsrSource() {
        val custom = AppSettings.asrSource == VoskModelCatalog.SOURCE_CUSTOM
        binding.etAsrUrl.visibility = if (custom) View.VISIBLE else View.GONE
        val giga = AppSettings.asrModel == AppSettings.ASR_MODEL_GIGAAM
        val large = AppSettings.asrModel == AppSettings.ASR_MODEL_LARGE
        binding.tvAsrHint.text = if (giga)
            "第三方 sherpa-onnx + GigaAM v2 俄语 CTC INT8，下载 ONNX 236 MB，使用 HF Mirror 优先，完成后验证固定 SHA-256；本机 CPU 做离线分段识别。模型独立于 Vosk，不需要 Vosk ZIP。ModelScope 尚无核验的同一文件。"
        else if (large)
            "Large 官方 ZIP 1,837,429,010 字节（约 1.84 GB）；原仓库说的 3.5 GB 是解压后模型目录，不是下载大小。已下载 ZIP 可用「导入 ZIP」，已解压目录请用「导入文件夹」。不支持 Android 手机流畅加载的保证；模型来源优先官方下载，无已核验的 ModelScope ZIP。"
        else "Small 已核验 HF Mirror 对应的 HF 文件及 SHA-256；自动模式优先国内 HF Mirror，失败切换官方来源。支持暂停、续传、ZIP CRC 与结构校验。"
    }
    private fun refreshAsrUi() {
        if (AppSettings.asrModel == AppSettings.ASR_MODEL_GIGAAM) {
            renderedAsr = GigaAmCatalog.MODEL_ID
            binding.etAsrUrl.setText(customUrls.getString("vosk:" + renderedAsr, ""))
            binding.btnImportAsr.text = "导入 GigaAM ONNX"
            binding.btnImportAsrFolder.visibility = View.GONE
            binding.btnDownloadAsr.text = "下载 / 继续下载 GigaAM v2 俄语 INT8（236 MB）"
            refreshAsrSource()
            binding.tvAsrStatus.text = if (ModelDownloader(this, GigaAmCatalog.SPEC).installed())
                "ONNX 已核验安装 · 返回主页将启动 sherpa-onnx CPU 语音识别"
            else "尚未安装 · 下载 236 MB ONNX，独立运行时，非 Vosk 模型"
            return
        }
        val spec = VoskModelCatalog.model(AppSettings.asrModel)
        binding.btnImportAsr.text = "导入 Vosk ZIP"
        binding.btnImportAsrFolder.visibility = View.VISIBLE
        renderedAsr = spec.id
        binding.etAsrUrl.setText(customUrls.getString("vosk:" + spec.id, ""))
        refreshAsrSource()
        binding.btnDownloadAsr.text = "下载 / 继续下载 " + spec.title
        val own = VoskArchiveInstaller(this, spec).installed()
        val legacy = if (!own) ModelManager.getModelDir(this, spec.id) != null else false
        binding.tvAsrStatus.text = when {
            own -> "已安装至应用专属目录 · 返回主界面自动加载"
            legacy -> "发现已有手动安装的模型 · 返回主界面可使用"
            else -> "未安装 · 点击上方按钮下载 " + spec.archiveName
        }
    }

    private fun startAsrOperation(isDownload: Boolean = false, action: suspend () -> Unit) {
        if (busy) return
        asrDownloading = isDownload
        setBusy(true)
        operationJob = uiScope.launch {
            try { action(); refreshAsrUi() }
            catch (e: CancellationException) {
                if (isDownload && !isDestroyed) binding.tvAsrStatus.text =
                    "已暂停 · 断点已保存，下次点击继续下载"
                throw e
            }
            catch (e: Exception) { binding.tvAsrStatus.text = "语音模型操作失败：" + e.message }
            finally { asrDownloading = false; setBusy(false) }
        }
    }
    private fun showAsrProgress(p: VoskArchiveInstaller.Progress) = runOnUiThread {
        if (!isDestroyed && !isFinishing) {
            val ratio = if (p.total > 0) (p.bytes * 100 / p.total).toInt().coerceIn(0, 100) else 0
            binding.progressAsr.progress = ratio
            binding.tvAsrStatus.text = p.stage + " · " + (p.bytes / 1024 / 1024) + " MiB" +
                if (p.total > 0) " · " + ratio + "%" else ""
        }
    }
    private fun refreshModelUi() {
        rendering = true
        val spec = selectedModel()
        renderedEngine = spec.engine
        val usesGguf = spec.engine != AppSettings.ENGINE_GEMMA4
        binding.radioTranslationEngine.check(when (spec.engine) {
            AppSettings.ENGINE_LMT -> binding.rbLmt.id
            AppSettings.ENGINE_HYMT2 -> hyMtButton.id
            else -> binding.rbGemma4.id
        })
        binding.radioInferenceBackend.check(if (usesGguf) binding.rbBackendCpu.id else when (AppSettings.inferenceBackend) {
            AppSettings.BACKEND_CPU -> binding.rbBackendCpu.id
            AppSettings.BACKEND_GPU -> binding.rbBackendGpu.id
            else -> binding.rbBackendAuto.id
        })
        binding.tvBackendNote.text = if (usesGguf)
            "${spec.title} 本版使用 ARM64 CPU 后端，GPU / NPU 尚未接入。返回主页面后释放旧引擎并重新加载。"
        else "Gemma 可使用 GPU 并回退 CPU。切换时先释放旧引擎，不同时驻留多套权重。"
        val origin = when (spec.engine) {
            AppSettings.ENGINE_LMT -> "原模型：NiuTrans；GGUF：mradermacher 社区量化。"
            AppSettings.ENGINE_HYMT2 -> "腾讯官方 GGUF；独立 Hunyuan 模板与架构校验。"
            else -> "LiteRT-LM 权重。"
        }
        binding.tvModelDescription.text = "${spec.title} · ${spec.sizeLabel}\n${spec.fileName}\n${origin}固定版本与 SHA-256。"
        binding.etModelUrl.setText(customUrls.getString(spec.engine, ""))
        refreshSourceUi()
        val shortName = when (spec.engine) {
            AppSettings.ENGINE_LMT -> "LMT"
            AppSettings.ENGINE_HYMT2 -> "HY-MT2"
            else -> "Gemma 4"
        }
        binding.btnDownloadModel.text = "下载 / 继续下载 $shortName"
        binding.tvModelStatus.text = if (ModelDownloader(this, spec).installed()) "已校验安装 · 可离线使用"
            else "尚未校验安装 · 请选择来源下载或从本地导入"
        rendering = false
        setBusy(busy)
    }
    private fun refreshSourceUi() {
        binding.etModelUrl.visibility = if (AppSettings.modelSource == AppSettings.SOURCE_CUSTOM) View.VISIBLE else View.GONE
        val spec = selectedModel()
        binding.tvSourceHint.text = if (spec.modelScopeUrl() != null)
            "已内置魔搭模型地址。所选来源失败后自动尝试其他来源；支持分块下载和断点续传。"
        else "此 LMT 量化文件暂无已核验的魔搭地址，将使用镜像和备用代理；来源失败自动切换。"
    }
    private fun startModelOperation(isDownload: Boolean = false, action: suspend () -> Unit) {
        if (busy) return
        downloading = isDownload
        setBusy(true)
        operationJob = uiScope.launch {
            try { action(); refreshModelUi() }
            catch (e: CancellationException) {
                if (isDownload && !isDestroyed) binding.tvModelStatus.text = "已暂停 · 已完成分块已保存，点击继续下载可恢复"
                throw e
            }
            catch (e: Exception) { binding.tvModelStatus.text = "操作未完成：${e.message}" }
            finally { setBusy(false); downloading = false }
        }
    }
    private fun showProgress(p: ModelDownloader.Progress) = runOnUiThread {
        if (!isDestroyed && !isFinishing) {
            val percent = if (p.expected > 0L) (p.received * 100 / p.expected).toInt().coerceIn(0, 100) else 0
            binding.progressModel.isIndeterminate = p.expected <= 0L
            binding.progressModel.progress = percent
            binding.tvModelStatus.text = "${p.stage} · ${p.received / (1024 * 1024)} MiB" + if (p.expected > 0L) " · $percent%" else ""
        }
    }
    private fun setBusy(value: Boolean) {
        busy = value
        listOf(binding.btnDownloadModel, binding.btnImportModel, binding.btnRemoveModel,
            binding.spinnerModelSource, binding.etModelUrl, binding.rbGemma4, binding.rbLmt, hyMtButton,
            binding.rbAsrSmall, binding.rbAsrLarge, binding.rbAsrGigaam, binding.spinnerAsrSource,
            binding.etAsrUrl, binding.btnDownloadAsr, binding.btnImportAsr,
            binding.btnImportAsrFolder, binding.btnRemoveAsr
            ).forEach { it.isEnabled = !value }
        val canChooseDevice = !value && AppSettings.translationEngine == AppSettings.ENGINE_GEMMA4
        listOf(binding.rbBackendCpu, binding.rbBackendAuto, binding.rbBackendGpu).forEach { it.isEnabled = canChooseDevice }
        binding.progressModel.visibility = if (value) View.VISIBLE else View.GONE
        binding.btnPauseModel.visibility = if (value && downloading) View.VISIBLE else View.GONE
        binding.progressAsr.visibility = if (value && asrDownloading) View.VISIBLE else View.GONE
        binding.btnPauseAsr.visibility = if (value && asrDownloading) View.VISIBLE else View.GONE
    }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("import_engine", pendingImportEngine)
        outState.putString("import_asr", pendingImportAsr)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { uiScope.cancel(); super.onDestroy() }
}
