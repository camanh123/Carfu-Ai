package org.stypox.dicio.asrbenchmark.metrics

import java.util.Locale

/**
 * RTF = transcription_time / audio_duration
 * Examples from Phase 3A spec:
 *  5s audio / 5s inference = 1.0
 *  5s audio / 2.5s inference = 0.5
 *  5s audio / 10s inference = 2.0
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
