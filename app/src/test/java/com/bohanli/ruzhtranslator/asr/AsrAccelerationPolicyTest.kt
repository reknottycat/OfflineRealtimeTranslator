package com.bohanli.ruzhtranslator.asr

import com.bohanli.ruzhtranslator.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Simulated Android inputs for routing. These tests do not invoke QNN or a physical device. */
class AsrAccelerationPolicyTest {
    private data class Device(val name: String, val androidApi: Int, val soc: String?)

    private val xiaomi15 = Device("Xiaomi 15 / 15 Pro / 15 Ultra", 35, " sm8750-ab ")
    private val xiaomi17 = Device("Xiaomi 17 / 17 Pro / 17 Pro Max / 17 Ultra", 36, "SM8850-1-AD")
    private val nonQualcomm = listOf(
        Device("Xiaomi 15S Pro", 35, "XRING O1"),
        Device("Xiaomi 15T", 35, "Dimensity 8400-Ultra"),
        Device("Xiaomi 15T Pro", 35, "Dimensity 9400+"),
        Device("Xiaomi 17T", 36, "Dimensity 8500-Ultra"),
        Device("Xiaomi 17T Pro", 36, "Dimensity 9500"),
        Device("Unidentified Xiaomi variant", 36, null),
        Device("Generic cloud emulator", 36, "ranchu")
    )
    private val multilingualModels = listOf(MultilingualAsrCatalog.NEMOTRON, MultilingualAsrCatalog.QWEN)

    @Test fun recognizesBothSnapdragonFamiliesAndNormalizesReportedSuffixes() {
        assertEquals(xiaomi15.name, "SM8750", chipset(xiaomi15))
        assertEquals(xiaomi17.name, "SM8850", chipset(xiaomi17))
        assertEquals("SM8750", AsrAccelerationPolicy.chipset(31, "SM8750"))
        assertEquals("SM8850", AsrAccelerationPolicy.chipset(36, "SM8850"))
    }

    @Test fun missingOrUnsupportedSocDoesNotMatchAPhoneFamilyName() {
        for (device in nonQualcomm) assertNull(device.name, chipset(device))
        for (soc in listOf("", "  ", "SM8650", "SM87500", "SM8850X", "Snapdragon 8 Elite")) {
            assertNull(soc, AsrAccelerationPolicy.chipset(36, soc))
        }
    }

    @Test fun androidBeforeSocModelApiDoesNotEnableQnn() {
        assertNull(AsrAccelerationPolicy.chipset(30, "SM8750"))
        assertNull(AsrAccelerationPolicy.chipset(30, "SM8850"))
    }

    @Test fun bothMultilingualModelsPreferReadyNpuOnBothSnapdragonFamilies() {
        for (device in listOf(xiaomi15, xiaomi17)) {
            for (model in multilingualModels) {
                assertEquals("${device.name}: $model", AsrBackend.NPU,
                    AsrAccelerationPolicy.backend(model, chipset(device), npuBuild = true, npuReady = true, cpuReady = true))
                assertEquals("${device.name}: $model without CPU weights", AsrBackend.NPU,
                    AsrAccelerationPolicy.backend(model, chipset(device), npuBuild = true, npuReady = true, cpuReady = false))
            }
        }
    }

    @Test fun cpuApkAlwaysUsesAvailableCpuWeights() {
        for (model in multilingualModels) {
            assertEquals(model, AsrBackend.CPU,
                AsrAccelerationPolicy.backend(model, chipset(xiaomi15), npuBuild = false, npuReady = true, cpuReady = true))
            assertNull(model,
                AsrAccelerationPolicy.backend(model, chipset(xiaomi15), npuBuild = false, npuReady = true, cpuReady = false))
        }
    }

    @Test fun absentNpuModelPackageFallsBackOnlyWhenCpuWeightsAreReady() {
        for (model in multilingualModels) {
            assertEquals(model, AsrBackend.CPU,
                AsrAccelerationPolicy.backend(model, chipset(xiaomi17), npuBuild = true, npuReady = false, cpuReady = true))
            assertNull(model,
                AsrAccelerationPolicy.backend(model, chipset(xiaomi17), npuBuild = true, npuReady = false, cpuReady = false))
        }
    }

    @Test fun unsupportedSocUsesCpuEvenIfAnNpuPackageIsPresent() {
        for (device in nonQualcomm) {
            for (model in multilingualModels) {
                assertEquals("${device.name}: $model", AsrBackend.CPU,
                    AsrAccelerationPolicy.backend(model, chipset(device), npuBuild = true, npuReady = true, cpuReady = true))
                assertNull("${device.name}: $model without CPU weights",
                    AsrAccelerationPolicy.backend(model, chipset(device), npuBuild = true, npuReady = true, cpuReady = false))
            }
        }
    }

    @Test fun legacyModelsKeepUsingCpuOnSnapdragon() {
        val legacyModels = listOf(
            AppSettings.ASR_MODEL_SMALL, AppSettings.ASR_MODEL_LARGE, AppSettings.ASR_MODEL_GIGAAM
        )
        for (model in legacyModels) {
            assertEquals(model, AsrBackend.CPU,
                AsrAccelerationPolicy.backend(model, chipset(xiaomi17), npuBuild = true, npuReady = true, cpuReady = true))
            assertNull(model,
                AsrAccelerationPolicy.backend(model, chipset(xiaomi17), npuBuild = true, npuReady = true, cpuReady = false))
        }
    }

    private fun chipset(device: Device): String? =
        AsrAccelerationPolicy.chipset(device.androidApi, device.soc)
}
