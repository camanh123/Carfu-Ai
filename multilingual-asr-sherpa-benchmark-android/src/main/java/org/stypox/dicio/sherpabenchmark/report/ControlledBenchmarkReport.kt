package org.stypox.dicio.sherpabenchmark.report

import org.stypox.dicio.sherpabenchmark.audio.ReferenceAudio
import org.stypox.dicio.sherpabenchmark.engine.ResourceSnapshot
import org.stypox.dicio.sherpabenchmark.engine.SoakResult
import org.stypox.dicio.sherpabenchmark.engine.ThreadBenchmarkResult
import org.stypox.dicio.sherpabenchmark.engine.ThreadConfigSummary
import org.stypox.dicio.sherpabenchmark.freeze.HardFreeze
import org.stypox.dicio.sherpabenchmark.engine.MemoryTrendHeuristic
import org.stypox.dicio.sherpabenchmark.metrics.MemorySnapshot
import org.stypox.dicio.sherpabenchmark.metrics.RealTimeFactor

object ControlledBenchmarkReport {
    fun render(
        modelName: String,
        modelHashes: String,
        sherpaVersion: String,
        reference: ReferenceAudio?,
        thread: ThreadBenchmarkResult?,
        soak: SoakResult?,
        pendingDecode: Int,
        previousEndedAbnormally: Boolean,
        lastCompletedIteration: Int,
        extraError: String = "",
    ): String = buildString {
        appendLine("=== CARFU PHASE 3B.1.2 CONTROLLED BENCHMARK ===")
        appendLine()
        appendLine("MODEL: $modelName")
        appendLine("MODEL HASHES:")
        appendLine(modelHashes.trimEnd())
        appendLine("SHERPA: $sherpaVersion")
        appendLine("EXECUTION_PROVIDER: ${HardFreeze.EXECUTION_PROVIDER}")
        appendLine("DECODING: ${HardFreeze.DECODING_METHOD}")
        appendLine()
        appendLine("INTERACTIVE_MODE: ${HardFreeze.INTERACTIVE_MODE}")
        appendLine("FIXED_AUDIO_BENCHMARK_MODE: ${HardFreeze.FIXED_AUDIO_BENCHMARK_MODE}")
        appendLine()
        appendLine("RAW_OUTPUT_UNMODIFIED: ${HardFreeze.yesNo(HardFreeze.RAW_OUTPUT_UNMODIFIED)}")
        appendLine("HOTWORDS_CONNECTED: ${HardFreeze.yesNo(HardFreeze.HOTWORDS_CONNECTED)}")
        appendLine("PHASE2A_CONNECTED: ${HardFreeze.yesNo(HardFreeze.PHASE2A_CONNECTED)}")
        appendLine("NLU_CONNECTED: ${HardFreeze.yesNo(HardFreeze.NLU_CONNECTED)}")
        appendLine("PRODUCTION_CONNECTED: ${HardFreeze.yesNo(HardFreeze.PRODUCTION_CONNECTED)}")
        appendLine()
        appendLine("NOTE: thermal/order bias may exist. Thread sequence is 1 then 2 then 4.")
        appendLine("Recognizer init time is reported separately and is NOT included in RTF.")
        appendLine("Fixed-audio RTF must not be compared to interactive simulated-streaming RTF.")
        appendLine()
        if (reference == null) {
            appendLine("REFERENCE_AUDIO_DURATION: (none)")
            appendLine("REFERENCE_AUDIO_SAMPLES: (none)")
            appendLine("REFERENCE_AUDIO_BYTES: (none)")
            appendLine("REFERENCE_AUDIO_SHA256: (none)")
        } else {
            appendLine("REFERENCE_AUDIO_DURATION: ${reference.durationMs}")
            appendLine("REFERENCE_AUDIO_SAMPLES: ${reference.sampleCount}")
            appendLine("REFERENCE_AUDIO_BYTES: ${reference.byteCount}")
            appendLine("REFERENCE_AUDIO_SHA256: ${reference.sha256}")
        }
        appendLine()
        appendLine("--- THREAD BENCHMARK ---")
        if (thread == null) {
            appendLine("(none)")
        } else {
            appendLine("BENCHMARK_START: ${thread.startEpochMs}")
            appendLine("BENCHMARK_DURATION_MS: ${thread.durationMs}")
            appendLine("THERMAL_STATUS_START: ${thread.thermalStart}")
            appendLine("THERMAL_STATUS_END: ${thread.thermalEnd}")
            appendLine("CANCELLED: ${HardFreeze.yesNo(thread.cancelled)}")
            thread.configs.forEach { append(configBlock(it)) }
            appendResourceBlock(thread.resources.copy(pendingDecode = pendingDecode))
            if (thread.errors.isNotEmpty()) {
                appendLine("ERRORS:")
                thread.errors.forEach { appendLine("  $it") }
            } else {
                appendLine("ERRORS: (none)")
            }
        }
        appendLine()
        appendLine("--- MEMORY SOAK ---")
        if (soak == null) {
            appendLine("(none)")
        } else {
            appendLine("THREADS: ${soak.threads}")
            appendLine("ITERATIONS: ${soak.rows.size} (planned ${soak.iterations})")
            appendLine("RECOGNIZER_PERSISTS_ACROSS_ITERATIONS: ${HardFreeze.yesNo(soak.recognizerPersistsAcrossIterations)}")
            appendLine("BENCHMARK_START: ${soak.startEpochMs}")
            appendLine("BENCHMARK_DURATION_MS: ${soak.durationMs}")
            appendLine("THERMAL_STATUS_START: ${soak.thermalStart}")
            appendLine("THERMAL_STATUS_END: ${soak.thermalEnd}")
            appendLine("CANCELLED: ${HardFreeze.yesNo(soak.cancelled)}")
            appendLine()
            appendLine("NATIVE_HEAP_START: ${soak.nativeHeapStart}")
            appendLine("NATIVE_HEAP_AFTER_WARMUP: ${soak.nativeHeapAfterWarmup}")
            appendLine("NATIVE_HEAP_MAX: ${soak.nativeHeapMax}")
            appendLine("NATIVE_HEAP_FINAL: ${soak.nativeHeapFinal}")
            appendLine()
            appendLine("PSS_START: ${soak.pssStartKb}")
            appendLine("PSS_MAX: ${soak.pssMaxKb}")
            appendLine("PSS_FINAL: ${soak.pssFinalKb}")
            appendLine()
            appendLine("JAVA_HEAP_START: ${soak.javaHeapStart}")
            appendLine("JAVA_HEAP_MAX: ${soak.javaHeapMax}")
            appendLine("JAVA_HEAP_FINAL: ${soak.javaHeapFinal}")
            appendLine()
            appendLine("AVAILABLE_MEM_START: ${soak.availMemStart}")
            appendLine("AVAILABLE_MEM_MIN: ${soak.availMemMin}")
            appendLine("AVAILABLE_MEM_FINAL: ${soak.availMemFinal}")
            appendLine()
            appendLine("MEMORY_DELTAS:")
            if (soak.deltas.isEmpty()) {
                appendLine("  (none)")
            } else {
                soak.deltas.forEach { appendLine("  ${it.label}: ${it.deltaBytes}") }
            }
            appendLine("MEMORY_TREND: ${soak.trend}")
            appendLine("MEMORY_TREND_HEURISTIC: ${MemoryTrendHeuristic.EXPLANATION}")
            appendLine()
            appendResourceBlock(soak.resources.copy(pendingDecode = pendingDecode))
            appendLine("SOAK ROWS (bounded scalars only):")
            soak.rows.forEach { row ->
                appendLine(
                    "  iter=${row.iteration} javaUsed=${row.javaUsedBytes} javaTotal=${row.javaTotalBytes} " +
                        "native=${row.nativeHeapBytes} pssKb=${row.pssKb} avail=${row.availMemBytes} " +
                        "lowMem=${row.lowMemory} recInit=${row.recognizerInitCount} " +
                        "streamC=${row.streamCreateCount} streamR=${row.streamReleaseCount} " +
                        "computeMs=${row.computeMs} rtf=${RealTimeFactor.format(row.rtf)} " +
                        "final=${row.rawFinal} err=${row.error.ifBlank { "(none)" }}",
                )
            }
            if (soak.errors.isNotEmpty()) {
                appendLine("ERRORS:")
                soak.errors.forEach { appendLine("  $it") }
            } else {
                appendLine("ERRORS: (none)")
            }
        }
        appendLine()
        appendLine("PREVIOUS BENCHMARK ENDED ABNORMALLY: ${HardFreeze.yesNo(previousEndedAbnormally)}")
        appendLine("LAST COMPLETED ITERATION: $lastCompletedIteration")
        if (extraError.isNotBlank()) {
            appendLine("ERROR: $extraError")
        }
    }

    fun compactComparison(thread: ThreadBenchmarkResult?): String {
        if (thread == null) return "(no thread benchmark)"
        return buildString {
            thread.configs.forEach { c ->
                appendLine("THREADS ${c.threads}")
                appendLine("median compute: ${c.medianComputeMs} ms")
                appendLine("median RTF: ${RealTimeFactor.format(c.medianRtf)}")
                appendLine(
                    "memory peak: " +
                        if (c.memoryPeakNativeBytes < 0L) {
                            "n/a"
                        } else {
                            MemorySnapshot.formatMb(c.memoryPeakNativeBytes)
                        },
                )
                appendLine()
            }
        }.trimEnd()
    }

    private fun StringBuilder.configBlock(c: ThreadConfigSummary) {
        appendLine()
        appendLine("THREADS=${c.threads}")
        appendLine("RECOGNIZER_INIT_MS: ${c.recognizerInitMs}")
        appendLine("WARMUP: ${runLine(c.warmup)}")
        c.measured.forEach { appendLine("${it.label}: ${runLine(it)}") }
        appendLine("MEDIAN: compute=${c.medianComputeMs} ms  min=${c.minComputeMs}  max=${c.maxComputeMs}")
        appendLine(
            "RTF_MEDIAN: ${RealTimeFactor.format(c.medianRtf)}  " +
                "min=${RealTimeFactor.format(c.minRtf)}  max=${RealTimeFactor.format(c.maxRtf)}",
        )
        appendLine("MEDIAN_FINAL_LATENCY_MS: ${c.medianFinalLatencyMs}")
        appendLine(
            "MEMORY: peakNative=" +
                if (c.memoryPeakNativeBytes < 0L) "n/a" else c.memoryPeakNativeBytes.toString(),
        )
    }

    private fun runLine(run: org.stypox.dicio.sherpabenchmark.engine.OfflineRunRecord?): String {
        if (run == null) return "(none)"
        return "computeMs=${run.computeMs} rtf=${RealTimeFactor.format(run.rtf)} " +
            "sha=${run.pcmSha256} final=${run.rawFinal} err=${run.error.ifBlank { "(none)" }}"
    }

    private fun StringBuilder.appendResourceBlock(r: ResourceSnapshot) {
        appendLine("RECOGNIZER_CREATED: ${r.recognizerCreated}")
        appendLine("RECOGNIZER_RELEASED: ${r.recognizerReleased}")
        appendLine("STREAM_CREATED: ${r.streamCreated}")
        appendLine("STREAM_RELEASED: ${r.streamReleased}")
        appendLine("ACTIVE_STREAMS: ${r.activeStreams}")
        appendLine("ACTIVE_DECODE: ${r.activeDecode}")
        appendLine("PENDING_DECODE: ${r.pendingDecode}")
    }
}
