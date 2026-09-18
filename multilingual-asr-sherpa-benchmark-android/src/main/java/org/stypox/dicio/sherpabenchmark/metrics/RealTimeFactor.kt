package org.stypox.dicio.sherpabenchmark.metrics

import java.util.Locale

/**
 * RTF = asr_compute_time / audio_duration
 */
object RealTimeFactor {
    fun compute(transcriptionMs: Long, audioDurationMs: Long): Double? {
        if (audioDurationMs <= 0L || transcriptionMs < 0L) return null
        return transcriptionMs.toDouble() / audioDurationMs.toDouble()
    }

    fun format(rtf: Double?): String {
        if (rtf == null || rtf.isNaN() || rtf.isInfinite()) return "n/a"
        return String.format(Locale.US, "%.3f", rtf)
    }
}
