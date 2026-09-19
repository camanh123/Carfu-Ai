package org.stypox.dicio.sherpabenchmark.freeze

/**
 * Diagnostic safety bounds for Phase 3B.1.1. Not production Voice behavior.
 */
object BenchmarkLimits {
    const val MAX_RECORDING_MS: Long = 15_000L
    const val AUTO_STOP_REASON: String = "BENCHMARK_MAX_DURATION"
    const val MANUAL_STOP_REASON: String = "MANUAL_STOP"
    const val LIFECYCLE_STOP_REASON: String = "ACTIVITY_LIFECYCLE"
    const val MAX_PARTIAL_EVENTS: Int = 32
    const val MAX_JOURNAL_PARTIAL_CHARS: Int = 400
    const val MAX_JOURNAL_STACK_CHARS: Int = 4000
    const val MAX_DECODE_PENDING: Int = 1
}
