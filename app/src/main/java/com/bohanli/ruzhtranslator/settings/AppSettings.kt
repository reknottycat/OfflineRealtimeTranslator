package com.bohanli.ruzhtranslator.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * App-wide settings backed by SharedPreferences.
 * Persists the user's ASR model choice and the output-language
 * drift-mitigation toggle.
 */
object AppSettings {
    private const val PREFS = "app_settings"
    private const val KEY_DRIFT_RETRY = "drift_retry_enabled"
    private const val KEY_ASR_MODEL   = "asr_model"
    private const val KEY_TRANSLATION_ENGINE = "translation_engine"
    private const val KEY_INFERENCE_BACKEND = "inference_backend"
    private const val KEY_MODEL_SOURCE = "model_source"
    private const val KEY_MODEL_GENERATION = "model_generation"

    const val ASR_MODEL_SMALL = "vosk-model-small-ru-0.22"
    const val ASR_MODEL_LARGE = "vosk-model-ru-0.42"
    const val ENGINE_GEMMA4 = "gemma4"
    const val ENGINE_LMT = "lmt"
    const val ENGINE_HYMT2 = "hymt2"
    const val BACKEND_AUTO = "auto"
    const val BACKEND_GPU = "gpu"
    const val BACKEND_CPU = "cpu"
    const val SOURCE_HF = "huggingface"
    const val SOURCE_HF_MIRROR = "hf-mirror"
    const val SOURCE_MODELSCOPE = "modelscope"

    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            synchronized(this) {
                if (prefs == null) {
                    val p = context.applicationContext
                        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    // One-shot migration from MainActivity's legacy prefs key.
                    if (!p.contains(KEY_ASR_MODEL)) {
                        val legacy = context.applicationContext
                            .getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
                            .getString(KEY_ASR_MODEL, null)
                        if (legacy != null) {
                            p.edit().putString(KEY_ASR_MODEL, legacy).apply()
                        }
                    }
                    prefs = p
                }
            }
        }
    }

    var driftRetryEnabled: Boolean
        get() = prefs?.getBoolean(KEY_DRIFT_RETRY, true) ?: true
        set(value) { prefs?.edit()?.putBoolean(KEY_DRIFT_RETRY, value)?.apply() }

    var asrModel: String
        get() = prefs?.getString(KEY_ASR_MODEL, ASR_MODEL_SMALL) ?: ASR_MODEL_SMALL
        set(value) { prefs?.edit()?.putString(KEY_ASR_MODEL, value)?.apply() }
    var translationEngine: String
        get() = prefs?.getString(KEY_TRANSLATION_ENGINE, ENGINE_GEMMA4) ?: ENGINE_GEMMA4
        set(value) { prefs?.edit()?.putString(KEY_TRANSLATION_ENGINE, value)?.apply() }

    var inferenceBackend: String
        get() = prefs?.getString(KEY_INFERENCE_BACKEND, BACKEND_AUTO) ?: BACKEND_AUTO
        set(value) { prefs?.edit()?.putString(KEY_INFERENCE_BACKEND, value)?.apply() }

    var modelSource: String
        get() = prefs?.getString(KEY_MODEL_SOURCE, SOURCE_HF) ?: SOURCE_HF
        set(value) { prefs?.edit()?.putString(KEY_MODEL_SOURCE, value)?.apply() }
    /** Revision counter for installed model files. Lets MainActivity safely reload after setup. */
    var modelGeneration: Int
        get() = prefs?.getInt(KEY_MODEL_GENERATION, 0) ?: 0
        set(value) { prefs?.edit()?.putInt(KEY_MODEL_GENERATION, value)?.apply() }

    fun notifyModelsChanged() { modelGeneration += 1 }
}
