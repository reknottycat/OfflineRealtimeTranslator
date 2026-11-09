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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val downloader by lazy { ModelDownloader(this) }

    private val sourceKeys = listOf(
        AppSettings.SOURCE_HF,
        AppSettings.SOURCE_HF_MIRROR,
        AppSettings.SOURCE_MODELSCOPE
    )
    private val importModel = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) startModelOperation {
            downloader.importFrom(uri, ::showProgress)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.init(this)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnBack.setOnClickListener { finish() }
        binding.tvVersion.text = "v3.3-alpha · Gemma 4 E2B"

        binding.radioAsrModel.check(
            if (AppSettings.asrModel == AppSettings.ASR_MODEL_LARGE)
                binding.rbAsrLarge.id else binding.rbAsrSmall.id
        )
        binding.radioAsrModel.setOnCheckedChangeListener { _, checkedId ->
            val name = if (checkedId == binding.rbAsrLarge.id)
                AppSettings.ASR_MODEL_LARGE else AppSettings.ASR_MODEL_SMALL
            if (name != AppSettings.asrModel) {
                AppSettings.asrModel = name
                toast(getString(R.string.asr_changed_toast))
            }
        }

        binding.radioTranslationEngine.check(binding.rbGemma4.id)
        // LMT's model/runtime contract is not yet confirmed: never silently use
        // Gemma and present its output as if it came from LMT.
        binding.rbLmt.isEnabled = false

        binding.radioInferenceBackend.check(when (AppSettings.inferenceBackend) {
            AppSettings.BACKEND_CPU -> binding.rbBackendCpu.id
            AppSettings.BACKEND_GPU -> binding.rbBackendGpu.id
            else -> binding.rbBackendAuto.id
        })
        binding.radioInferenceBackend.setOnCheckedChangeListener { _, checkedId ->
            AppSettings.inferenceBackend = when (checkedId) {
                binding.rbBackendCpu.id -> AppSettings.BACKEND_CPU
                binding.rbBackendGpu.id -> AppSettings.BACKEND_GPU
                else -> AppSettings.BACKEND_AUTO
            }
        }

        binding.switchDriftRetry.isChecked = AppSettings.driftRetryEnabled
        binding.switchDriftRetry.setOnCheckedChangeListener { _, checked ->
            AppSettings.driftRetryEnabled = checked
        }

        val options = arrayOf(
            "Hugging Face（官方）",
            "HF Mirror（镜像）",
            "ModelScope（自定义直链）"
        )
        binding.spinnerModelSource.adapter = ArrayAdapter(
            this, R.layout.spinner_item, options
        ).also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }
        val selected = sourceKeys.indexOf(AppSettings.modelSource).coerceAtLeast(0)
        binding.spinnerModelSource.setSelection(selected)
        binding.spinnerModelSource.onItemSelectedListener = object :
            AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                AppSettings.modelSource = sourceKeys[position]
                binding.etModelUrl.visibility =
                    if (sourceKeys[position] == AppSettings.SOURCE_MODELSCOPE)
                        View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        binding.btnDownloadModel.setOnClickListener {
            val url = ModelCatalog.sourceUrl(AppSettings.modelSource)
                ?: binding.etModelUrl.text.toString().trim()
            if (url.isBlank()) {
                toast("请粘贴可信 ModelScope 镜像的 HTTPS 模型文件直链")
                return@setOnClickListener
            }
            startModelOperation { downloader.download(url, ::showProgress) }
        }
        binding.btnImportModel.setOnClickListener {
            importModel.launch(arrayOf("*/*"))
        }
        binding.btnRemoveModel.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("删除 Gemma 4 本地模型？")
                .setMessage("将释放约 2.59 GB 空间。返回主界面后需要重新下载才能翻译。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除") { _, _ ->
                    startModelOperation { downloader.deleteModel() }
                }.show()
        }
        updateModelStatus()
    }

    private fun startModelOperation(action: suspend () -> Unit) {
        setBusy(true)
        uiScope.launch {
            try {
                action()
                updateModelStatus()
            } catch (e: Exception) {
                binding.tvModelStatus.text = "操作失败：${e.message}"
                toast("模型操作未完成，下载可以继续")
            } finally {
                setBusy(false)
            }
        }
    }

    private fun showProgress(p: ModelDownloader.Progress) {
        runOnUiThread {
            val percent = (p.received * 100 / p.expected).toInt().coerceIn(0, 100)
            binding.progressModel.progress = percent
            binding.tvModelStatus.text = "${p.stage} · $percent% · ${p.received / (1024 * 1024)} MiB"
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.btnDownloadModel.isEnabled = !busy
        binding.btnImportModel.isEnabled = !busy
        binding.btnRemoveModel.isEnabled = !busy
        binding.spinnerModelSource.isEnabled = !busy
        binding.progressModel.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun updateModelStatus() {
        binding.tvModelStatus.text = if (downloader.installed()) {
            "已安装 Gemma 4 E2B · 可离线运行"
        } else {
            "未安装 Gemma 4 · 需要下载约 2.59 GB"
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }
}
