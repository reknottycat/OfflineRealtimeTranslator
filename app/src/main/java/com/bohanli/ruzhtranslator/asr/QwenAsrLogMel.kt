package com.bohanli.ruzhtranslator.asr

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin

/** Whisper features for the Qwen3-ASR encoder exported by LiteRT. */
internal object QwenAsrLogMel {
    const val MEL_BINS = 128

    private const val SAMPLE_RATE = 16_000
    private const val HOP = 160
    private const val MAX_FRAMES = 500
    private const val FFT_SIZE = 400
    private const val FFT_FACTOR = 20
    private const val FFT_BINS = FFT_SIZE / 2 + 1
    private const val MIN_POWER = 1e-10f
    private const val MIN_LOG_POWER = -10f

    private val hannWindow = FloatArray(FFT_SIZE) { index ->
        (0.5 - 0.5 * cos(2 * PI * index / FFT_SIZE)).toFloat()
    }
    private val twiddleReal = FloatArray(FFT_SIZE) { index ->
        cos(-2 * PI * index / FFT_SIZE).toFloat()
    }
    private val twiddleImag = FloatArray(FFT_SIZE) { index ->
        sin(-2 * PI * index / FFT_SIZE).toFloat()
    }
    private val dftReal = FloatArray(FFT_SIZE) { index ->
        twiddleReal[index / FFT_FACTOR * (index % FFT_FACTOR) * FFT_FACTOR % FFT_SIZE]
    }
    private val dftImag = FloatArray(FFT_SIZE) { index ->
        twiddleImag[index / FFT_FACTOR * (index % FFT_FACTOR) * FFT_FACTOR % FFT_SIZE]
    }
    private val melFilters = createMelFilters()

    /** Returns [128, frames] in row-major order, using at most five seconds of 16 kHz PCM. */
    fun compute(samples: FloatArray, frames: Int): FloatArray {
        require(frames in 1..MAX_FRAMES) { "Qwen3-ASR expects at most $MAX_FRAMES mel frames" }
        val paddedSize = frames * HOP
        val sampleCount = minOf(samples.size, paddedSize)
        val output = FloatArray(MEL_BINS * frames) { MIN_LOG_POWER }
        val windowed = FloatArray(FFT_SIZE)
        val intermediateReal = FloatArray(FFT_SIZE)
        val intermediateImag = FloatArray(FFT_SIZE)
        val power = FloatArray(FFT_BINS)
        var maxLogPower = MIN_LOG_POWER

        // Frames whose entire analysis window lies in zero padding have minimum log power.
        val activeFrames = minOf(frames, (sampleCount + FFT_SIZE / 2 + HOP - 1) / HOP)
        for (frame in 0 until activeFrames) {
            val start = frame * HOP - FFT_SIZE / 2
            for (offset in windowed.indices) {
                val index = reflectIndex(start + offset, paddedSize)
                windowed[offset] = if (index < sampleCount) samples[index] * hannWindow[offset] else 0f
            }
            powerSpectrum(windowed, intermediateReal, intermediateImag, power)

            for ((mel, filter) in melFilters.withIndex()) {
                var melPower = 0f
                for (offset in filter.weights.indices) {
                    melPower += power[filter.firstBin + offset] * filter.weights[offset]
                }
                val logPower = log10(maxOf(MIN_POWER, melPower))
                output[mel * frames + frame] = logPower
                maxLogPower = maxOf(maxLogPower, logPower)
            }
        }

        val floor = maxLogPower - 8f
        for (index in output.indices) {
            output[index] = (maxOf(output[index], floor) + 4f) / 4f
        }
        return output
    }

    private fun reflectIndex(index: Int, size: Int): Int {
        if (index in 0 until size) return index
        val period = 2 * (size - 1)
        val wrapped = (index % period + period) % period
        return minOf(wrapped, period - wrapped)
    }

    private fun powerSpectrum(
        windowed: FloatArray,
        intermediateReal: FloatArray,
        intermediateImag: FloatArray,
        power: FloatArray,
    ) {
        // A 400-point FFT decomposes into two 20-point DFT passes and a twiddle factor.
        // The second pass only computes the nonnegative frequencies of this real signal.
        for (frequency in 0 until FFT_FACTOR) {
            val row = frequency * FFT_FACTOR
            for (column in 0 until FFT_FACTOR) {
                var real = 0f
                var imag = 0f
                for (sample in 0 until FFT_FACTOR) {
                    val value = windowed[column + sample * FFT_FACTOR]
                    real += value * dftReal[row + sample]
                    imag += value * dftImag[row + sample]
                }
                val twiddle = frequency * column
                intermediateReal[row + column] = real * twiddleReal[twiddle] - imag * twiddleImag[twiddle]
                intermediateImag[row + column] = real * twiddleImag[twiddle] + imag * twiddleReal[twiddle]
            }
        }

        for (bin in power.indices) {
            val inputRow = (bin % FFT_FACTOR) * FFT_FACTOR
            val weightRow = (bin / FFT_FACTOR) * FFT_FACTOR
            var real = 0f
            var imag = 0f
            for (column in 0 until FFT_FACTOR) {
                val inputReal = intermediateReal[inputRow + column]
                val inputImag = intermediateImag[inputRow + column]
                val weightReal = dftReal[weightRow + column]
                val weightImag = dftImag[weightRow + column]
                real += inputReal * weightReal - inputImag * weightImag
                imag += inputReal * weightImag + inputImag * weightReal
            }
            power[bin] = real * real + imag * imag
        }
    }

    private data class MelFilter(val firstBin: Int, val weights: FloatArray)

    private fun createMelFilters(): Array<MelFilter> {
        val linearSpacing = 200.0 / 3.0
        val logStartHz = 1000.0
        val logStartMel = logStartHz / linearSpacing
        val logStep = ln(6.4) / 27.0
        val maxMel = logStartMel + ln((SAMPLE_RATE / 2.0) / logStartHz) / logStep
        val edges = DoubleArray(MEL_BINS + 2) { index ->
            val mel = index * maxMel / (MEL_BINS + 1)
            if (mel < logStartMel) mel * linearSpacing else logStartHz * exp(logStep * (mel - logStartMel))
        }
        val binSpacing = SAMPLE_RATE.toDouble() / FFT_SIZE

        return Array(MEL_BINS) { mel ->
            val left = edges[mel]
            val center = edges[mel + 1]
            val right = edges[mel + 2]
            val firstBin = ceil(left / binSpacing).toInt()
            val lastBin = minOf(FFT_BINS - 1, floor(right / binSpacing).toInt())
            val normalization = 2 / (right - left)
            val weights = FloatArray(lastBin - firstBin + 1) { offset ->
                val hz = (firstBin + offset) * binSpacing
                val weight = minOf((hz - left) / (center - left), (right - hz) / (right - center))
                (maxOf(0.0, weight) * normalization).toFloat()
            }
            MelFilter(firstBin, weights)
        }
    }
}
