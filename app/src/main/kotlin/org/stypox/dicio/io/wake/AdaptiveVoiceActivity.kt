package org.stypox.dicio.io.wake

import kotlin.math.max

/**
 * Adaptive energy gate for CARFU wake. Exact-zero frames are never voice.
 * Model scores without voice activity cannot accumulate toward ACCEPT.
 */
class AdaptiveVoiceActivity {
    var noiseRms: Double = INITIAL_NOISE_RMS
        private set

    data class Result(
        val peak: Int,
        val rms: Double,
        val exactZero: Boolean,
        val isVoice: Boolean,
    )

    fun observe(peak: Int, rms: Double): Result {
        val exactZero = peak == 0 && rms <= 0.0
        if (exactZero) {
            return Result(peak, rms, true, false)
        }
        val voiceThreshold = max(MIN_VOICE_RMS, noiseRms * NOISE_MULTIPLIER)
        val isVoice = peak >= MIN_VOICE_PEAK && rms >= voiceThreshold
        if (!isVoice) {
            noiseRms = (NOISE_EMA * noiseRms) + ((1.0 - NOISE_EMA) * rms)
        }
        return Result(peak, rms, false, isVoice)
    }

    fun reset() {
        noiseRms = INITIAL_NOISE_RMS
    }

    companion object {
        const val MIN_VOICE_PEAK = 80
        const val MIN_VOICE_RMS = 40.0
        const val INITIAL_NOISE_RMS = 30.0
        const val NOISE_MULTIPLIER = 2.5
        const val NOISE_EMA = 0.95
    }
}
