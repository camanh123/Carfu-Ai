package org.stypox.dicio.asrbenchmark.report

import org.stypox.dicio.asrbenchmark.config.ContextPrompt
import org.stypox.dicio.asrbenchmark.corpus.SpokenTestTargets
import org.stypox.dicio.asrbenchmark.metrics.RealTimeFactor
import java.util.Locale

object DiagnosticReport {
    fun render(
        phaseBase: String,
        applicationId: String,
        whisperSource: String,
        whisperCommit: String,
        whisperLicense: String,
        modelName: String,
        modelMultilingual: Boolean,
        modelSizeBytes: Long,
        modelQuantization: String,
        modelSha256: String,
        modelSource: String,
        minSdk: Int,
        abi: String,
        nativeOptimization: String,
        current: BenchmarkSession?,
        history: List<BenchmarkSession>,
        extraError: String,
        cpuCores: Int,
        nativeSystemInfo: String,
    ): String = buildString {
        appendLine("=== CARFU PHASE 3A MULTILINGUAL ASR DIAGNOSTIC ===")
        appendLine("PHASE_BASE: $phaseBase")
        appendLine("MODULE: multilingual-asr-benchmark-android")
        appendLine("APPLICATION_ID: $applicationId")
        appendLine()
        appendLine("WHISPER_CPP_SOURCE: $whisperSource")
        appendLine("WHISPER_CPP_VERSION_OR_COMMIT: $whisperCommit")
        appendLine("WHISPER_LICENSE: $whisperLicense")
        appendLine()
        appendLine("MODEL: $modelName")
        appendLine("MODEL_MULTILINGUAL: ${if (modelMultilingual) "YES" else "NO"}")
        appendLine("MODEL_SIZE: $modelSizeBytes bytes")
        appendLine("MODEL_QUANTIZATION: $modelQuantization")
        appendLine("MODEL_SHA256: $modelSha256")
        appendLine("MODEL_SOURCE: $modelSource")
        appendLine("MODEL_PACKAGING_STRATEGY: downloaded at build from official Hugging Face ggml repo, SHA256-verified, packaged as APK asset, copied to app filesDir at runtime")
        appendLine()
        appendLine("ANDROID_MIN_SDK: $minSdk")
        appendLine("TARGET_ABI: $abi")
        appendLine("NATIVE_OPTIMIZATION: $nativeOptimization")
        appendLine("CPU_CORES_AVAILABLE: $cpuCores")
        appendLine("WHISPER_SYSTEM_INFO: $nativeSystemInfo")
        appendLine()
        appendLine("LANGUAGE_AUTO: YES")
        appendLine("LANGUAGE_VI: YES")
        appendLine("CONTEXT_TOGGLE: YES")
        appendLine("CONTEXT_PROMPT: ${ContextPrompt.VOCABULARY_HINT}")
        appendLine()
        appendLine("AUDIO_FORMAT: 16 kHz mono PCM16 LE converted to float32")
        appendLine("THREAD_OPTIONS: 2, 4 (default 4)")
        appendLine()
        appendLine("RAW_OUTPUT_UNMODIFIED: YES")
        appendLine("PHASE2A_CONNECTED: NO")
        appendLine("NLU_CONNECTED: NO")
        appendLine("PRODUCTION_CONNECTED: NO")
        appendLine()
        appendLine("--- CURRENT SESSION ---")
        if (current == null) {
            appendLine("(none)")
        } else {
            append(sessionBlock(current))
        }
        if (extraError.isNotBlank()) {
            appendLine("ERROR: $extraError")
        }
        appendLine()
        appendLine("--- HISTORY (${history.size}) ---")
        if (history.isEmpty()) {
            appendLine("(empty)")
        } else {
            history.forEach { append(sessionBlock(it)); appendLine() }
        }
        appendLine(SpokenTestTargets.asPlainText())
        appendLine("NOTE: spoken targets are NOT expected transcripts and are NOT used to repair ASR output.")
    }

    fun sessionBlock(s: BenchmarkSession): String = buildString {
        appendLine("SESSION #${s.sequence}")
        appendLine("ENGINE: ${s.engine}")
        appendLine("MODEL: ${s.model}")
        appendLine("MODEL SIZE: ${s.modelSizeBytes} bytes")
        appendLine("LANGUAGE MODE: ${s.languageMode.displayName}")
        appendLine("CONTEXT: ${if (s.contextOn) "ON" else "OFF"}")
        appendLine("CONTEXT PROMPT: ${if (s.contextOn) s.contextPrompt else "(disabled)"}")
        appendLine("THREADS: ${s.nThreads} (device cores=${s.cpuCores})")
        appendLine("RECORDING STATUS: ${s.recordingStatus}")
        appendLine("AUDIO DURATION: ${s.audioDurationMs} ms")
        appendLine("RAW TRANSCRIPT: ${s.rawTranscript}")
        appendLine("DETECTED LANGUAGE: ${s.detectedLanguage.ifBlank { "n/a" }}")
        appendLine(
            "LANGUAGE CONFIDENCE: " +
                if (s.languageConfidence < 0f) "n/a" else String.format(Locale.US, "%.4f", s.languageConfidence),
        )
        appendLine("SEGMENTS: ${s.segmentCount}")
        appendLine("MODEL LOAD TIME: ${s.modelLoadTimeMs} ms")
        appendLine("TRANSCRIPTION TIME: ${s.transcriptionTimeMs} ms")
        appendLine("REAL-TIME FACTOR: ${RealTimeFactor.format(s.rtf)}")
        s.memoryBefore?.let { appendLine(it.summaryLine("APP MEMORY BEFORE")) }
        s.memoryAfter?.let { appendLine(it.summaryLine("APP MEMORY AFTER")) }
        s.peakMemory?.let { appendLine(it.summaryLine()) }
        appendLine("CPU/THREAD CONFIG: n_threads=${s.nThreads}; ${s.nativeSystemInfo}")
        appendLine("ERROR: ${s.error.ifBlank { "(none)" }}")
    }
}
