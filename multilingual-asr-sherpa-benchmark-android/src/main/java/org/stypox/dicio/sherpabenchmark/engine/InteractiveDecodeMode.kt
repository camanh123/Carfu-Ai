package org.stypox.dicio.sherpabenchmark.engine

/**
 * Interactive simulated-streaming A/B.
 *
 * LEGACY: Phase 3B.1.1/3B.1.2 200 ms tick → DecodeGate (device baseline).
 * OPTIMIZED: Phase 3B.1.3 adaptive coalescing scheduler (device fail baseline).
 * BOUNDED: Phase 3B.1.4 audio-progress budget (max 5 partials).
 */
enum class InteractiveDecodeMode {
    LEGACY,
    OPTIMIZED,
    BOUNDED,
    ;

    val reportName: String
        get() = when (this) {
            LEGACY -> "LEGACY"
            OPTIMIZED -> "OPTIMIZED_3B1_3"
            BOUNDED -> "BOUNDED"
        }

    companion object {
        val DEFAULT: InteractiveDecodeMode = BOUNDED
    }
}

object InteractiveOptimizeLimits {
    /** Midpoint of the 500–700 ms first-partial target. Unchanged 3B.1.3 fail baseline. */
    const val FIRST_PARTIAL_AUDIO_MS: Long = 600L
    const val MIN_NEW_AUDIO_MS: Long = 200L
    const val MAX_NEW_AUDIO_WAIT_MS: Long = 800L
    const val OPTIMIZED_POLL_MS: Long = 100L
    const val SAMPLE_RATE_HZ: Int = 16_000
}

enum class PartialScheduleDecision {
    REQUEST,
    COALESCE,
    SKIP_TOO_EARLY,
    SKIP_NO_NEW_AUDIO,
    SKIP_BUSY,
}
