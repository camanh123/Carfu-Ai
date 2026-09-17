package org.stypox.dicio.asrbenchmark.report

import org.stypox.dicio.asrbenchmark.config.LanguageMode
import org.stypox.dicio.asrbenchmark.metrics.MemorySnapshot
import org.stypox.dicio.asrbenchmark.metrics.PeakMemory

data class BenchmarkSession(
    val sequence: Int,
    val timestampEpochMs: Long,
    val engine: String,
    val model: String,
    val modelSizeBytes: Long,
    val modelSha256: String,
    val languageMode: LanguageMode,
    val contextOn: Boolean,
    val contextPrompt: String,
    val nThreads: Int,
    val cpuCores: Int,
    val nativeSystemInfo: String,
    val audioDurationMs: Long,
    val modelLoadTimeMs: Long,
    val transcriptionTimeMs: Long,
    val rtf: Double?,
    val rawTranscript: String,
    val detectedLanguage: String,
    val languageConfidence: Float,
    val segmentCount: Int,
    val memoryBefore: MemorySnapshot?,
    val memoryAfter: MemorySnapshot?,
    val peakMemory: PeakMemory?,
    val error: String,
    val recordingStatus: String,
) {
    fun shortHistoryLine(): String {
        val rtfText = if (rtf == null) "n/a" else String.format(java.util.Locale.US, "%.2f", rtf)
        val preview = rawTranscript.replace('\n', ' ').take(80)
        val err = if (error.isBlank()) "" else " ERR=${error.take(40)}"
        return "#$sequence ${languageMode.name} ctx=${if (contextOn) "ON" else "OFF"} " +
            "thr=$nThreads ${audioDurationMs}ms rtf=$rtfText lang=$detectedLanguage " +
            "raw=\"$preview\"$err"
    }
}
