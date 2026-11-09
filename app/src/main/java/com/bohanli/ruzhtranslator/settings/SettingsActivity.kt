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
    private lateinit var hyMtButton: AppCompatRadioButton
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
        // Extend the existing settings card; no replacement of the speech/subtitle UI.
        hyMtButton = AppCompatRadioButton(this).apply {
            id = R.id.rb_hymt2
            setText(R.string.engine_hymt2)
            setTextColor(binding.rbLmt.textColors)
            buttonTintList = binding.rbLmt.buttonTintList
            textSize = binding.rbLmt.textSize / resources.displayMetrics.scaledDensity
            minHeight = (48 * resources.displayMetrics.density).toInt()
        }
        binding.radioTranslationEngine.addView(hyMtButton,
            RadioGroup.LayoutParams(RadioGroup.LayoutParams.MATCH_PARENT, RadioGroup.LayoutParams.WRAP_CONTENT))
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
            binding.spinnerModelSource, binding.etModelUrl, binding.rbGemma4, binding.rbLmt, hyMtButton,
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
