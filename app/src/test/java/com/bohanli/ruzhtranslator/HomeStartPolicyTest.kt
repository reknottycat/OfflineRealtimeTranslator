package com.bohanli.ruzhtranslator

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeStartPolicyTest {
    @Test fun cleanPreviewInstallShowsModelSetupNotDeadButton() {
        assertEquals(HomeStartPolicy.Mode.NEED_ASR,
            HomeStartPolicy.mode(asrReady = false, translationReady = false))
    }

    @Test fun installedGigaAmWithoutTranslationIsValidTranscription() {
        assertEquals(HomeStartPolicy.Mode.ASR_ONLY,
            HomeStartPolicy.mode(asrReady = true, translationReady = false))
    }

    @Test fun bothModelsInstalledEnableBilingualTranslation() {
        assertEquals(HomeStartPolicy.Mode.FULL_TRANSLATION,
            HomeStartPolicy.mode(asrReady = true, translationReady = true))
    }

    @Test fun translationWithoutAsrStillNeedsMicrophoneRecognitionModel() {
        assertEquals(HomeStartPolicy.Mode.NEED_ASR,
            HomeStartPolicy.mode(asrReady = false, translationReady = true))
    }
}
