package com.bohanli.ruzhtranslator

/**
 * Start is always interactive: absence of one or both independent model families
 * must not turn the home screen into an unusable disabled button.
 */
internal object HomeStartPolicy {
    enum class Mode { NEED_ASR, ASR_ONLY, FULL_TRANSLATION }

    fun mode(asrReady: Boolean, translationReady: Boolean): Mode = when {
        !asrReady -> Mode.NEED_ASR
        translationReady -> Mode.FULL_TRANSLATION
        else -> Mode.ASR_ONLY
    }
}
