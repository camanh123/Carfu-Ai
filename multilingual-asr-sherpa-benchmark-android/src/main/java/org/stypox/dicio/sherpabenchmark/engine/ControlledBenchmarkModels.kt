package org.stypox.dicio.sherpabenchmark.engine

import org.stypox.dicio.sherpabenchmark.freeze.FixedAudioLimits
import org.stypox.dicio.sherpabenchmark.metrics.MemorySnapshot
import org.stypox.dicio.sherpabenchmark.metrics.PeakMemory
import org.stypox.dicio.sherpabenchmark.metrics.RealTimeFactor

data class OfflineRunRecord(
    val label: String,
    val threads: Int,
    val warmup: Boolean,
    val computeMs: Long,
    val audioMs: Long,
    val rtf: Double?,
    val rawFinal: String,
    val recognizerInitMs: Long,
    val memoryBefore: MemorySnapshot?,
    val memoryPeak: PeakMemory?,
    val memoryAfter: MemorySnapshot?,
    val pcmSha256: String,
    val decodeCalls: Int,
    val error: String = "",
)

data class ThreadConfigSummary(
    val threads: Int,
    val recognizerInitMs: Long,
    val warmup: OfflineRunRecord?,
    val measured: List<OfflineRunRecord>,
    val medianComputeMs: Long,
    val minComputeMs: Long,
    val maxComputeMs: Long,
    val medianRtf: Double?,
    val minRtf: Double?,
    val maxRtf: Double?,
    val medianFinalLatencyMs: Long,
    val memoryPeakNativeBytes: Long,
)

data class ThreadBenchmarkResult(
    val pcmSha256: String,
    val audioMs: Long,
    val sampleCount: Int,
    val byteCount: Int,
    val configs: List<ThreadConfigSummary>,
    val cancelled: Boolean,
    val startEpochMs: Long,
    val durationMs: Long,
    val thermalStart: String,
    val thermalEnd: String,
    val resources: ResourceSnapshot,
    val errors: List<String>,
)

data class SoakIterationRecord(
    val iteration: Int,
    val warmup: Boolean,
    val javaUsedBytes: Long,
    val javaTotalBytes: Long,
    val nativeHeapBytes: Long,
    val pssKb: Long,
    val availMemBytes: Long,
    val lowMemory: Boolean,
    val recognizerInitCount: Int,
    val streamCreateCount: Int,
    val streamReleaseCount: Int,
    val computeMs: Long,
    val rtf: Double?,
    val rawFinal: String,
    val error: String,
)

data class SoakResult(
    val threads: Int,
    val iterations: Int,
    val pcmSha256: String,
    val audioMs: Long,
    val warmup: SoakIterationRecord?,
    val rows: List<SoakIterationRecord>,
    val nativeHeapStart: Long,
    val nativeHeapAfterWarmup: Long,
    val nativeHeapMax: Long,
    val nativeHeapFinal: Long,
    val pssStartKb: Long,
    val pssMaxKb: Long,
    val pssFinalKb: Long,
    val javaHeapStart: Long,
    val javaHeapMax: Long,
    val javaHeapFinal: Long,
    val availMemStart: Long,
    val availMemMin: Long,
    val availMemFinal: Long,
    val deltas: List<NamedDelta>,
    val trend: MemoryTrend,
    val cancelled: Boolean,
    val startEpochMs: Long,
    val durationMs: Long,
    val thermalStart: String,
    val thermalEnd: String,
    val resources: ResourceSnapshot,
    val recognizerPersistsAcrossIterations: Boolean,
    val errors: List<String>,
)

object SoakDeltas {
    fun compute(nativeByIteration: Map<Int, Long>, plannedIterations: Int): List<NamedDelta> {
        val pairs = mutableListOf(1 to 5, 5 to 10, 10 to 20, 20 to 30)
        if (plannedIterations >= 50) {
            pairs += 30 to 40
            pairs += 40 to 50
        }
        return pairs.mapNotNull { (a, b) ->
            val va = nativeByIteration[a] ?: return@mapNotNull null
            val vb = nativeByIteration[b] ?: return@mapNotNull null
            NamedDelta("$a->$b", vb - va)
        }
    }
}

fun summarizeThreadConfig(
    threads: Int,
    initMs: Long,
    warmup: OfflineRunRecord?,
    measured: List<OfflineRunRecord>,
): ThreadConfigSummary {
    val compute = measured.map { it.computeMs }
    val rtf = measured.mapNotNull { it.rtf }
    val peaks = measured.mapNotNull { it.memoryPeak?.peakNativeHeapBytes } +
        listOfNotNull(warmup?.memoryPeak?.peakNativeHeapBytes)
    return ThreadConfigSummary(
        threads = threads,
        recognizerInitMs = initMs,
        warmup = warmup,
        measured = measured,
        medianComputeMs = if (compute.isEmpty()) 0L else Stats.medianLong(compute),
        minComputeMs = if (compute.isEmpty()) 0L else Stats.minLong(compute),
        maxComputeMs = if (compute.isEmpty()) 0L else Stats.maxLong(compute),
        medianRtf = if (rtf.isEmpty()) null else Stats.medianDouble(rtf),
        minRtf = if (rtf.isEmpty()) null else Stats.minDouble(rtf),
        maxRtf = if (rtf.isEmpty()) null else Stats.maxDouble(rtf),
        medianFinalLatencyMs = if (compute.isEmpty()) 0L else Stats.medianLong(compute),
        memoryPeakNativeBytes = peaks.maxOrNull() ?: -1L,
    )
}

fun boundTranscript(text: String): String =
    text.take(FixedAudioLimits.MAX_STORED_TRANSCRIPT_CHARS)
