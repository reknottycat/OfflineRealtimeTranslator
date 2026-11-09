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

    const val ASR_MODEL_SMALL = "vosk-model-small-ru-0.22"
    const val ASR_MODEL_LARGE = "vosk-model-ru-0.42"
    const val ENGINE_GEMMA4 = "gemma4"
    const val ENGINE_LMT = "lmt"
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
}
