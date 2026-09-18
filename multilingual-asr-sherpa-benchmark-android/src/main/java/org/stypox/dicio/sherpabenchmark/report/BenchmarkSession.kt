package org.stypox.dicio.sherpabenchmark.report

import org.stypox.dicio.sherpabenchmark.engine.PartialEvent
import org.stypox.dicio.sherpabenchmark.metrics.MemorySnapshot
import org.stypox.dicio.sherpabenchmark.metrics.PeakMemory

data class BenchmarkSession(
    val sessionId: String,
    val sequence: Int,
    val timestampEpochMs: Long,
    val engine: String,
    val model: String,
    val modelFiles: String,
    val executionProvider: String,
    val recognitionMode: String,
    val nativeStreamingModel: Boolean,
    val simulatedStreaming: Boolean,
    val decodingMethod: String,
    val threadCount: Int,
    val cpuCores: Int,
    val androidAbi: String,
    val androidApi: Int,
    val cpuFeatures: String,
    val audioStartEpochMs: Long,
    val stopEpochMs: Long,
    val audioDurationMs: Long,
    val timeToFirstAudioChunkMs: Long,
    val timeToFirstNonEmptyPartialMs: Long?,
    val partialCount: Int,
    val lastPartialEpochMs: Long,
    val stopToFinalMs: Long,
    val totalAsrComputeMs: Long,
    val rtf: Double?,
    val recognizerInitMs: Long,
    val finalRawTranscript: String,
    val latestPartial: String,
    val partials: List<PartialEvent>,
    val memoryBefore: MemorySnapshot?,
    val memoryDuringSampledPeak: PeakMemory?,
    val memoryAfter: MemorySnapshot?,
    val error: String,
    val recordingStatus: String,
) {
    fun shortHistoryLine(): String {
        val rtfText = if (rtf == null) "n/a" else String.format(java.util.Locale.US, "%.2f", rtf)
        val preview = finalRawTranscript.replace('\n', ' ').take(80)
        val err = if (error.isBlank()) "" else " ERR=${error.take(40)}"
        val ttf = timeToFirstNonEmptyPartialMs?.toString() ?: "n/a"
        return "#$sequence id=$sessionId thr=$threadCount ${audioDurationMs}ms " +
            "ttfPartial=${ttf}ms stopFinal=${stopToFinalMs}ms rtf=$rtfText raw=\"$preview\"$err"
    }
}
