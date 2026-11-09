package com.bohanli.ruzhtranslator.settings

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bohanli.ruzhtranslator.R
import com.bohanli.ruzhtranslator.core.ModelCatalog
import com.bohanli.ruzhtranslator.core.ModelDownloader
import com.bohanli.ruzhtranslator.databinding.ActivitySettingsBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val sourceKeys = listOf("auto", AppSettings.SOURCE_HF, AppSettings.SOURCE_HF_MIRROR, AppSettings.SOURCE_MODELSCOPE)
    private val customUrls by lazy { getSharedPreferences("model_custom_urls", MODE_PRIVATE) }
    private var rendering = false
    private var busy = false
    private var renderedEngine = AppSettings.ENGINE_GEMMA4
    private var pendingImportEngine = AppSettings.ENGINE_GEMMA4
    private fun selectedModel() = ModelCatalog.forEngine(AppSettings.translationEngine)
    private val importModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val installer = ModelDownloader(this, ModelCatalog.forEngine(pendingImportEngine))
            startModelOperation { installer.importFrom(uri, ::showProgress) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.init(this)
        pendingImportEngine = savedInstanceState?.getString("import_engine") ?: AppSettings.translationEngine
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnBack.setOnClickListener { finish() }
        binding.tvVersion.text = "v3.5-alpha · Gemma 4 / LMT / HY-MT2"
        binding.radioAsrModel.check(if (AppSettings.asrModel == AppSettings.ASR_MODEL_LARGE)
            binding.rbAsrLarge.id else binding.rbAsrSmall.id)
        binding.radioAsrModel.setOnCheckedChangeListener { _, id ->
            AppSettings.asrModel = if (id == binding.rbAsrLarge.id) AppSettings.ASR_MODEL_LARGE else AppSettings.ASR_MODEL_SMALL
        }
        binding.radioTranslationEngine.setOnCheckedChangeListener { _, id ->
            if (!rendering && !busy) {
                customUrls.edit().putString(renderedEngine, binding.etModelUrl.text.toString()).apply()
                AppSettings.translationEngine = when (id) {
                    binding.rbLmt.id -> AppSettings.ENGINE_LMT
                    binding.rbHymt2.id -> AppSettings.ENGINE_HYMT2
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
            arrayOf("自动（HF → HF Mirror）", "Hugging Face（托管源）", "HF Mirror（第三方镜像）", "ModelScope / 自定义 HTTPS"))
            .also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }
        binding.spinnerModelSource.setSelection(sourceKeys.indexOf(AppSettings.modelSource).coerceAtLeast(0))
        binding.spinnerModelSource.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                AppSettings.modelSource = sourceKeys[position]
                binding.etModelUrl.visibility = if (sourceKeys[position] == AppSettings.SOURCE_MODELSCOPE) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        binding.btnDownloadModel.setOnClickListener {
            val spec = selectedModel()
            val installer = ModelDownloader(this, spec)
            val urls = when (AppSettings.modelSource) {
                "auto" -> listOf(spec.url("huggingface.co"), spec.url("hf-mirror.com"))
                AppSettings.SOURCE_MODELSCOPE -> listOf(binding.etModelUrl.text.toString().trim())
                else -> listOfNotNull(ModelCatalog.sourceUrl(AppSettings.modelSource, spec.engine))
            }
            if (urls.isEmpty() || urls.any { it.isBlank() }) { toast("请填写对应模型的 HTTPS 文件直链"); return@setOnClickListener }
            customUrls.edit().putString(spec.engine, binding.etModelUrl.text.toString()).apply()
            startModelOperation { installer.download(urls, ::showProgress) }
        }
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
    }

    private fun refreshModelUi() {
        rendering = true
        val spec = selectedModel()
        renderedEngine = spec.engine
        val nativeCpu = spec.nativeProfile != null
        binding.radioTranslationEngine.check(when (spec.engine) {
            AppSettings.ENGINE_LMT -> binding.rbLmt.id
            AppSettings.ENGINE_HYMT2 -> binding.rbHymt2.id
            else -> binding.rbGemma4.id
        })
        binding.radioInferenceBackend.check(if (nativeCpu) binding.rbBackendCpu.id else when (AppSettings.inferenceBackend) {
            AppSettings.BACKEND_CPU -> binding.rbBackendCpu.id
            AppSettings.BACKEND_GPU -> binding.rbBackendGpu.id
            else -> binding.rbBackendAuto.id
        })
        binding.tvBackendNote.text = when (spec.engine) {
            AppSettings.ENGINE_HYMT2 -> "HY-MT2 使用独立的混元提示词与权重，当前为 ARM64 CPU；逐句完成后显示译文，不展示思考片段。返回主页面生效。"
            AppSettings.ENGINE_LMT -> "LMT 本版使用优化后的 ARM64 CPU 后端；不会把 Gemma 的 GPU 设置误标为 LMT 加速。返回主页面生效。"
            else -> "Gemma 可使用 GPU 并回退 CPU。切换时先释放旧引擎，仅驻留一套翻译权重。"
        }
        val attribution = when (spec.engine) {
            AppSettings.ENGINE_HYMT2 -> "腾讯官方 GGUF；固定版本、精确字节数与 SHA-256。"
            AppSettings.ENGINE_LMT -> "原模型：NiuTrans；GGUF：mradermacher 社区量化。固定版本与 SHA-256。"
            else -> "LiteRT-LM 权重；固定版本与 SHA-256。"
        }
        binding.tvModelDescription.text = "${spec.title} · ${spec.sizeLabel}\n${spec.fileName}\n$attribution"
        binding.etModelUrl.setText(customUrls.getString(spec.engine, ""))
        binding.btnDownloadModel.text = "下载 / 继续下载 ${when (spec.engine) {
            AppSettings.ENGINE_HYMT2 -> "HY-MT2"
            AppSettings.ENGINE_LMT -> "LMT"
            else -> "Gemma 4"
        }}"
        binding.tvModelStatus.text = if (ModelDownloader(this, spec).installed()) "已校验安装 · 可离线使用"
            else "尚未校验安装 · 请选择来源下载或从本地导入"
        rendering = false
        setBusy(busy)
    }

    private fun startModelOperation(action: suspend () -> Unit) {
        if (busy) return
        setBusy(true)
        uiScope.launch {
            try { action(); refreshModelUi() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { binding.tvModelStatus.text = "操作未完成：${e.message}" }
            finally { setBusy(false) }
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
            binding.spinnerModelSource, binding.etModelUrl, binding.rbGemma4, binding.rbLmt, binding.rbHymt2,
            binding.rbAsrSmall, binding.rbAsrLarge).forEach { it.isEnabled = !value }
        val canChooseDevice = !value && AppSettings.translationEngine == AppSettings.ENGINE_GEMMA4
        listOf(binding.rbBackendCpu, binding.rbBackendAuto, binding.rbBackendGpu).forEach { it.isEnabled = canChooseDevice }
        binding.progressModel.visibility = if (value) View.VISIBLE else View.GONE
    }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("import_engine", pendingImportEngine)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { uiScope.cancel(); super.onDestroy() }
}
