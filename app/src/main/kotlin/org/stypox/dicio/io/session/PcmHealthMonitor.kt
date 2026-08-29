package org.stypox.dicio.io.session

import kotlin.math.sqrt

/**
 * UIS7862 can report [android.media.AudioRecord.RECORDSTATE_RECORDING] while every sample is 0.
 * Initialized/recording state is not proof of healthy PCM.
 */
class PcmHealthMonitor(
    private val deadWindowMs: Long = DEAD_WINDOW_MS,
    private val maxRestarts: Int = MAX_RESTARTS,
) {
    enum class Action { HEALTHY, ACCUMULATING_ZERO, RESTART, DEAD_KEEP }

    private var exactZeroSinceMs: Long = -1L
    private var restartsUsed: Int = 0

    fun onRecorderOpened() {
        exactZeroSinceMs = -1L
        restartsUsed = 0
    }

    fun onFrame(
        nowMs: Long,
        recording: Boolean,
        peak: Int,
        rms: Double,
    ): Action {
        if (!recording) {
            exactZeroSinceMs = -1L
            return Action.HEALTHY
        }
        val exactZero = peak == 0 && rms <= 0.0
        if (!exactZero) {
            exactZeroSinceMs = -1L
            restartsUsed = 0
            return Action.HEALTHY
        }
        if (exactZeroSinceMs < 0L) {
            exactZeroSinceMs = nowMs
        }
        val elapsed = nowMs - exactZeroSinceMs
        if (elapsed < deadWindowMs) {
            return Action.ACCUMULATING_ZERO
        }
        if (restartsUsed < maxRestarts) {
            restartsUsed += 1
            exactZeroSinceMs = nowMs
            return Action.RESTART
        }
        return Action.DEAD_KEEP
    }

    companion object {
        const val DEAD_WINDOW_MS = 2_000L
        const val MAX_RESTARTS = 1
        const val SAMPLE_RATE_HZ = 16_000

        fun peakAndRms(samples: ShortArray, length: Int): Pair<Int, Double> {
            val n = length.coerceIn(0, samples.size)
            if (n <= 0) return 0 to 0.0
            var peak = 0
            var sumSq = 0.0
            for (i in 0 until n) {
                val v = samples[i].toInt()
                val abs = if (v < 0) -v else v
                if (abs > peak) peak = abs
                sumSq += v.toDouble() * v.toDouble()
            }
            return peak to sqrt(sumSq / n)
        }

        fun isExactZero(peak: Int, rms: Double): Boolean = peak == 0 && rms <= 0.0
    }
}
