package org.stypox.dicio.sherpabenchmark.report

import org.stypox.dicio.sherpabenchmark.PhaseInfo
import org.stypox.dicio.sherpabenchmark.corpus.SpokenTestTargets
import org.stypox.dicio.sherpabenchmark.freeze.HardFreeze
import org.stypox.dicio.sherpabenchmark.metrics.RealTimeFactor

object DiagnosticReport {
    fun render(
        applicationId: String,
        sherpaSource: String,
        sherpaVersion: String,
        sherpaLicense: String,
        modelName: String,
        modelArchitecture: String,
        modelLanguage: String,
        modelQuantization: String,
        modelFileSizes: String,
        modelFileSha256: String,
        minSdk: Int,
        targetSdk: Int,
        abi: String,
        deviceApi: Int,
        cpuCores: Int,
        cpuFeatures: String,
        threadOptions: String,
        current: BenchmarkSession?,
        history: List<BenchmarkSession>,
        extraError: String,
        previousEndedAbnormally: Boolean = false,
        lastJournal: String = "",
        maxRecordingMs: Long = org.stypox.dicio.sherpabenchmark.freeze.BenchmarkLimits.MAX_RECORDING_MS,
    ): String = buildString {
        appendLine("=== CARFU PHASE 3B.1.4 SHERPA ASR DIAGNOSTIC ===")
        appendLine()
        appendLine("PHASE_BASE: ${PhaseInfo.PHASE_BASE}")
        appendLine("MODULE: ${PhaseInfo.MODULE}")
        appendLine("APPLICATION_ID: $applicationId")
        appendLine()
        appendLine("SHERPA_ONNX_SOURCE: $sherpaSource")
        appendLine("SHERPA_ONNX_VERSION_OR_COMMIT: $sherpaVersion")
        appendLine("SHERPA_ONNX_LICENSE: $sherpaLicense")
        appendLine()
        appendLine("MODEL: $modelName")
        appendLine("MODEL_ARCHITECTURE: $modelArchitecture")
        appendLine("MODEL_LANGUAGE: $modelLanguage")
        appendLine("MODEL_QUANTIZATION: $modelQuantization")
        appendLine()
        appendLine("MODEL FILE SIZES:")
        appendLine(modelFileSizes)
        appendLine("MODEL FILE SHA256:")
        appendLine(modelFileSha256)
        appendLine()
        appendLine("ANDROID_MIN_SDK: $minSdk")
        appendLine("ANDROID_TARGET_SDK: $targetSdk")
        appendLine("TARGET_ABI: $abi")
        appendLine("DEVICE_API: $deviceApi")
        appendLine("CPU_CORES_AVAILABLE: $cpuCores")
        appendLine("CPU_FEATURES: $cpuFeatures")
        appendLine()
        appendLine("EXECUTION_PROVIDER: ${HardFreeze.EXECUTION_PROVIDER}")
        appendLine()
        appendLine("RECOGNITION_ARCHITECTURE: ${HardFreeze.RECOGNITION_ARCHITECTURE}")
        appendLine("RECOGNITION_MODE: ${HardFreeze.RECOGNITION_MODE}")
        appendLine("NATIVE_STREAMING_MODEL: ${HardFreeze.yesNo(HardFreeze.NATIVE_STREAMING_MODEL)}")
        appendLine("SIMULATED_STREAMING: ${HardFreeze.yesNo(HardFreeze.SIMULATED_STREAMING)}")
        appendLine()
        appendLine("DECODING_METHOD: ${HardFreeze.DECODING_METHOD}")
        appendLine()
        appendLine("AUDIO_FORMAT: ${HardFreeze.AUDIO_FORMAT}")
        appendLine()
        appendLine("THREAD_OPTIONS: $threadOptions")
        appendLine("MAX_RECORDING_DURATION_MS: $maxRecordingMs")
        appendLine("AUTO_STOP_REASON_IF_LIMIT: ${org.stypox.dicio.sherpabenchmark.freeze.BenchmarkLimits.AUTO_STOP_REASON}")
        appendLine()
        appendLine("RAW_OUTPUT_UNMODIFIED: ${HardFreeze.yesNo(HardFreeze.RAW_OUTPUT_UNMODIFIED)}")
        appendLine("HOTWORDS_CONNECTED: ${HardFreeze.yesNo(HardFreeze.HOTWORDS_CONNECTED)}")
        appendLine("PHASE2A_CONNECTED: ${HardFreeze.yesNo(HardFreeze.PHASE2A_CONNECTED)}")
        appendLine("NLU_CONNECTED: ${HardFreeze.yesNo(HardFreeze.NLU_CONNECTED)}")
        appendLine("PRODUCTION_CONNECTED: ${HardFreeze.yesNo(HardFreeze.PRODUCTION_CONNECTED)}")
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
        appendLine("PREVIOUS SESSION ENDED ABNORMALLY: ${HardFreeze.yesNo(previousEndedAbnormally)}")
        appendLine("--- LAST SESSION JOURNAL ---")
        appendLine(lastJournal.ifBlank { "(none)" })
        appendLine()
        appendLine("--- HISTORY ---")
        if (history.isEmpty()) {
            appendLine("(empty)")
        } else {
            history.forEach { append(sessionBlock(it)); appendLine() }
        }
        appendLine(SpokenTestTargets.asPlainText())
        appendLine("NOTE: test targets are UI/instructions only and are NOT passed into recognition.")
    }

    fun sessionBlock(s: BenchmarkSession): String = buildString {
        appendLine("SESSION ID: ${s.sessionId}")
        appendLine("SEQUENCE: ${s.sequence}")
        appendLine("ENGINE: ${s.engine}")
        appendLine("MODEL: ${s.model}")
        appendLine("MODEL FILES: ${s.modelFiles}")
        appendLine("EXECUTION PROVIDER: ${s.executionProvider}")
        appendLine("RECOGNITION MODE: ${s.recognitionMode}")
        appendLine("NATIVE_STREAMING_MODEL: ${HardFreeze.yesNo(s.nativeStreamingModel)}")
        appendLine("SIMULATED_STREAMING: ${HardFreeze.yesNo(s.simulatedStreaming)}")
        appendLine("DECODING_METHOD: ${s.decodingMethod}")
        appendLine("THREAD COUNT: ${s.threadCount} (device cores=${s.cpuCores})")
        appendLine("ANDROID ABI: ${s.androidAbi}")
        appendLine("ANDROID API: ${s.androidApi}")
        appendLine("CPU FEATURES: ${s.cpuFeatures}")
        appendLine("AUDIO START: ${s.audioStartEpochMs}")
        appendLine("STOP: ${s.stopEpochMs}")
        appendLine("AUDIO DURATION ms: ${s.audioDurationMs}")
        appendLine("TIME TO FIRST AUDIO CHUNK ms: ${s.timeToFirstAudioChunkMs}")
        appendLine(
            "TIME TO FIRST NON-EMPTY PARTIAL ms: " +
                (s.timeToFirstNonEmptyPartialMs?.toString() ?: "N/A"),
        )
        appendLine("PARTIAL COUNT: ${s.partialCount}")
        appendLine("CHUNK COUNT: ${s.chunkCount}")
        appendLine("DECODE COUNT: ${s.decodeCount}")
        appendLine("MAX DECODE DURATION ms: ${s.maxDecodeMs}")
        appendLine("PENDING DECODE COUNT: ${s.pendingDecodeCount}")
        appendLine("INTERACTIVE MODE: ${s.interactiveMode}")
        appendLine("PARTIAL DECODE COUNT: ${s.partialDecodeCount}")
        appendLine("FINAL_DECODE_COUNT: ${s.finalDecodeCount}")
        appendLine("TOTAL DECODE COUNT: ${s.decodeCount}")
        appendLine("DECODE_REQUESTED: ${s.decodeRequested}")
        appendLine("DECODE_EXECUTED: ${s.decodeExecuted}")
        appendLine("DECODE_COALESCED: ${s.decodeCoalesced}")
        appendLine("DECODE_SKIPPED_BUSY: ${s.decodeSkippedBusy}")
        appendLine("DECODE_DROPPED_BUDGET: ${s.decodeDroppedBudget}")
        appendLine("DECODE_DROPPED_STOP: ${s.decodeDroppedStop}")
        appendLine("FINAL SAMPLE COUNT: ${s.finalSampleCount}")
        if (s.boundedPartialLogs.isNotEmpty()) {
            appendLine("BOUNDED PARTIAL LOG:")
            s.boundedPartialLogs.forEach { p ->
                appendLine(
                    "  PARTIAL_INDEX=${p.index} AUDIO_SNAPSHOT_MS=${p.audioSnapshotMs} " +
                        "NEW_AUDIO_SINCE_PREVIOUS_PARTIAL_MS=${p.newAudioSincePreviousMs} " +
                        "DECODE_DURATION_MS=${p.decodeDurationMs}",
                )
            }
        }
        appendLine("AUTO_STOP_REASON: ${s.autoStopReason.ifBlank { "(none)" }}")
        appendLine("LAST PARTIAL timestamp: ${s.lastPartialEpochMs}")
        appendLine("STOP -> FINAL latency ms: ${s.stopToFinalMs}")
        appendLine("TOTAL ASR COMPUTE TIME ms: ${s.totalAsrComputeMs}")
        appendLine("REAL-TIME FACTOR: ${RealTimeFactor.format(s.rtf)}")
        appendLine("MODEL / RECOGNIZER INIT TIME ms: ${s.recognizerInitMs}")
        appendLine("LATEST PARTIAL: ${s.latestPartial.ifEmpty { "(none)" }}")
        appendLine("FINAL RAW TRANSCRIPT: ${s.finalRawTranscript}")
        if (s.partials.isNotEmpty()) {
            appendLine("PARTIAL TIMING HISTORY:")
            s.partials.forEach { p ->
                appendLine(
                    "  #${p.index} elapsed=${p.sessionElapsedMs}ms wall=${p.wallClockEpochMs} " +
                        "decode=${p.decodeMs}ms audioDecoded=${p.audioMsDecoded}ms text=${p.text}",
                )
            }
        }
        s.memoryBefore?.let { appendLine(it.summaryLine("APP MEMORY BEFORE")) }
        s.memoryDuringSampledPeak?.let { appendLine(it.summaryLine()) }
        s.memoryAfter?.let { appendLine(it.summaryLine("APP MEMORY AFTER")) }
        appendLine("RECORDING STATUS: ${s.recordingStatus}")
        appendLine("ERROR: ${s.error.ifBlank { "(none)" }}")
    }
}
