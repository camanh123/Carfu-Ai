package org.stypox.dicio.sherpabenchmark.engine

import org.stypox.dicio.sherpabenchmark.freeze.BenchmarkLimits

object RecordingPolicy {
    fun shouldAutoStop(audioDurationMs: Long, maxMs: Long = BenchmarkLimits.MAX_RECORDING_MS): Boolean =
        audioDurationMs >= maxMs

    fun autoStopReason(): String = BenchmarkLimits.AUTO_STOP_REASON
}
