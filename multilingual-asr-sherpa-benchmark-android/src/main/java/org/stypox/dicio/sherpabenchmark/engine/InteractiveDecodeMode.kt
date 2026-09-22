package org.stypox.dicio.sherpabenchmark.engine

/**
 * Interactive simulated-streaming A/B. LEGACY is the Phase 3B.1.1/3B.1.2 path
 * (decode tick every 200 ms through DecodeGate). OPTIMIZED only requests a
 * full-utterance partial when the previous decode is done and enough new audio
 * has arrived.
 */
enum class InteractiveDecodeMode {
    LEGACY,
    OPTIMIZED,
    ;

    companion object {
        val DEFAULT: InteractiveDecodeMode = OPTIMIZED
    }
}

object InteractiveOptimizeLimits {
    /** Midpoint of the 500–700 ms first-partial target. */
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
