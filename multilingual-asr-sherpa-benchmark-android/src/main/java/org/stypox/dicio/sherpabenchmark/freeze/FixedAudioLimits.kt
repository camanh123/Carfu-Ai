package org.stypox.dicio.sherpabenchmark.freeze

/**
 * Phase 3B.1.2 controlled-benchmark bounds. Not production Voice behavior.
 * Interactive 15 s limit and DecodeGate are unchanged (see BenchmarkLimits).
 */
object FixedAudioLimits {
    const val WARMUP_RUNS: Int = 1
    const val MEASURED_RUNS: Int = 3
    val THREAD_SEQUENCE: List<Int> = listOf(1, 2, 4)
    val SOAK_OPTIONS: List<Int> = listOf(10, 30, 50)
    const val DEFAULT_SOAK_ITERATIONS: Int = 30
    const val DEFAULT_SOAK_THREADS: Int = 1
    const val MAX_SOAK_ROWS: Int = 50
    const val MAX_STORED_TRANSCRIPT_CHARS: Int = 200
    const val MAX_REPORT_CHARS: Int = 96_000
    const val MAX_JOURNAL_CHARS: Int = 32_000
}
