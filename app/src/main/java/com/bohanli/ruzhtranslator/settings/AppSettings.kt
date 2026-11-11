package com.bohanli.ruzhtranslator.settings

import android.content.Context
import android.content.SharedPreferences

/** Persisted preferences; existing Gemma/LMT selections and model paths are preserved. */
object AppSettings {
    private const val PREFS = "app_settings"
    private const val KEY_DRIFT_RETRY = "drift_retry_enabled"
    private const val KEY_ASR_MODEL = "asr_model"
    private const val KEY_ASR_SOURCE = "asr_download_source"
    private const val KEY_ASR_LANGUAGE = "asr_language"
    private const val KEY_ASR_QNN = "asr_qnn"
    private const val KEY_TRANSLATION_ENGINE = "translation_engine"
    private const val KEY_INFERENCE_BACKEND = "inference_backend"
    private const val KEY_MODEL_SOURCE = "model_source"
    private const val KEY_MODEL_GENERATION = "model_generation"
    private const val KEY_SOURCE_POLICY_VERSION = "model_source_policy_version"
    const val ASR_MODEL_SMALL = "vosk-model-small-ru-0.22"
    const val ASR_MODEL_LARGE = "vosk-model-ru-0.42"
    const val ASR_MODEL_GIGAAM = "sherpa-gigaam-v2-ru"
    const val ENGINE_GEMMA4 = "gemma4"
    const val ENGINE_LMT = "lmt"
    const val ENGINE_HYMT2 = "hy-mt2"
    const val BACKEND_AUTO = "auto"
    const val BACKEND_GPU = "gpu"
    const val BACKEND_CPU = "cpu"
    const val SOURCE_HF = "huggingface"
    const val SOURCE_HF_MIRROR = "hf-mirror"
    const val SOURCE_MODELSCOPE = "modelscope"
    const val SOURCE_AUTO = "auto"
    const val SOURCE_CN_PROXY = "cn-proxy"
    const val SOURCE_CUSTOM = "custom"
    @Volatile private var prefs: SharedPreferences? = null
    fun init(context: Context) {
        if (prefs == null) synchronized(this) {
            if (prefs == null) {
                val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                if (!p.contains(KEY_ASR_MODEL)) {
                    val legacy = context.applicationContext.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
                        .getString(KEY_ASR_MODEL, null)
                    if (legacy != null) p.edit().putString(KEY_ASR_MODEL, legacy).apply()
                }
                // v3.5 defaulted to HF without any user action. Migrate that default once;
                // afterwards an explicit preferred-source choice remains persistent.
                if (p.getInt(KEY_SOURCE_POLICY_VERSION, 0) < 1) {
                    val previous = p.getString(KEY_MODEL_SOURCE, null)
                    val migration = p.edit().putInt(KEY_SOURCE_POLICY_VERSION, 1)
                    if (previous == null || previous == SOURCE_HF) migration.putString(KEY_MODEL_SOURCE, SOURCE_AUTO)
                    migration.apply()
                }
                prefs = p
            }
        }
    }
    var driftRetryEnabled: Boolean
        get() = prefs?.getBoolean(KEY_DRIFT_RETRY, true) ?: true
        set(value) { prefs?.edit()?.putBoolean(KEY_DRIFT_RETRY, value)?.apply() }
    var asrModel: String
        get() = prefs?.getString(KEY_ASR_MODEL, ASR_MODEL_SMALL) ?: ASR_MODEL_SMALL
        set(value) { prefs?.edit()?.putString(KEY_ASR_MODEL, value)?.apply() }
    var asrSource: String
        get() = prefs?.getString(KEY_ASR_SOURCE, "auto") ?: "auto"
        set(value) { prefs?.edit()?.putString(KEY_ASR_SOURCE, value)?.apply() }
    var asrLanguage: String
        get() = prefs?.getString(KEY_ASR_LANGUAGE, "auto") ?: "auto"
        set(value) { prefs?.edit()?.putString(KEY_ASR_LANGUAGE, value)?.apply() }
    var preferAsrQnn: Boolean
        get() = prefs?.getBoolean(KEY_ASR_QNN, true) ?: true
        set(value) { prefs?.edit()?.putBoolean(KEY_ASR_QNN, value)?.apply() }
    var translationEngine: String
        get() = prefs?.getString(KEY_TRANSLATION_ENGINE, ENGINE_HYMT2) ?: ENGINE_HYMT2
        set(value) { prefs?.edit()?.putString(KEY_TRANSLATION_ENGINE, value)?.apply() }
    var inferenceBackend: String
        get() = prefs?.getString(KEY_INFERENCE_BACKEND, BACKEND_AUTO) ?: BACKEND_AUTO
        set(value) { prefs?.edit()?.putString(KEY_INFERENCE_BACKEND, value)?.apply() }
    var modelSource: String
        get() = prefs?.getString(KEY_MODEL_SOURCE, SOURCE_AUTO) ?: SOURCE_AUTO
        set(value) { prefs?.edit()?.putString(KEY_MODEL_SOURCE, value)?.apply() }
    var modelGeneration: Int
        get() = prefs?.getInt(KEY_MODEL_GENERATION, 0) ?: 0
        set(value) { prefs?.edit()?.putInt(KEY_MODEL_GENERATION, value)?.apply() }
    fun notifyModelsChanged() { modelGeneration += 1 }
}
