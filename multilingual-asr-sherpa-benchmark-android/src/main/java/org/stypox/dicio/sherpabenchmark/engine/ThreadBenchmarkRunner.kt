package org.stypox.dicio.sherpabenchmark.engine

import org.stypox.dicio.sherpabenchmark.freeze.FixedAudioLimits
import org.stypox.dicio.sherpabenchmark.metrics.MemorySnapshot
import org.stypox.dicio.sherpabenchmark.metrics.PeakMemory
import org.stypox.dicio.sherpabenchmark.metrics.RealTimeFactor

/**
 * Sequential 1/2/4 thread comparison on identical PCM.
 * One offline FINAL decode per run. No 200 ms simulated-streaming loop.
 */
class ThreadBenchmarkRunner(
    private val decodeOnce: (FloatArray, Int) -> String,
    private val ensureThreads: (Int) -> Long,
    private val memoryNow: () -> MemorySnapshot?,
    private val peakAround: ((() -> String) -> Pair<String, PeakMemory?>)? = null,
    private val nanoTime: () -> Long = { System.nanoTime() },
    private val wallClock: () -> Long = { System.currentTimeMillis() },
    private val threadSequence: List<Int> = FixedAudioLimits.THREAD_SEQUENCE,
    private val warmupRuns: Int = FixedAudioLimits.WARMUP_RUNS,
    private val measuredRuns: Int = FixedAudioLimits.MEASURED_RUNS,
) {
    var lastDecodeCallCount: Int = 0
        private set
    var overlappingRejected: Int = 0
        private set
    private val inDecode = java.util.concurrent.atomic.AtomicBoolean(false)

    fun run(
        samples: FloatArray,
        sampleRate: Int,
        pcmSha256: String,
        audioMs: Long,
        sampleCount: Int,
        byteCount: Int,
        cancelled: () -> Boolean,
        progress: (completed: Int, total: Int, label: String) -> Unit,
        thermalStart: String,
        thermalNow: () -> String,
    ): ThreadBenchmarkResult {
        lastDecodeCallCount = 0
        val start = wallClock()
        val errors = ArrayList<String>()
        val configs = ArrayList<ThreadConfigSummary>()
        val total = threadSequence.size * (warmupRuns + measuredRuns)
        var completed = 0
        for (threads in threadSequence) {
            if (cancelled()) break
            val initMs = try {
                ensureThreads(threads)
            } catch (t: Throwable) {
                errors += "ensureThreads($threads): ${t.message}"
                break
            }
            var warmup: OfflineRunRecord? = null
            val measured = ArrayList<OfflineRunRecord>()
            repeat(warmupRuns) { w ->
                if (cancelled()) return@repeat
                completed += 1
                progress(completed, total, "THREADS=$threads warmup ${w + 1}")
                warmup = oneRun(
                    label = "WARMUP",
                    threads = threads,
                    warmup = true,
                    samples = samples,
                    sampleRate = sampleRate,
                    pcmSha256 = pcmSha256,
                    audioMs = audioMs,
                    initMs = initMs,
                    errors = errors,
                )
            }
            repeat(measuredRuns) { i ->
                if (cancelled()) return@repeat
                completed += 1
                progress(completed, total, "THREADS=$threads run ${i + 1}")
                measured += oneRun(
                    label = "RUN${i + 1}",
                    threads = threads,
                    warmup = false,
                    samples = samples,
                    sampleRate = sampleRate,
                    pcmSha256 = pcmSha256,
                    audioMs = audioMs,
                    initMs = 0L,
                    errors = errors,
                )
            }
            configs += summarizeThreadConfig(threads, initMs, warmup, measured)
        }
        return ThreadBenchmarkResult(
            pcmSha256 = pcmSha256,
            audioMs = audioMs,
            sampleCount = sampleCount,
            byteCount = byteCount,
            configs = configs,
            cancelled = cancelled(),
            startEpochMs = start,
            durationMs = wallClock() - start,
            thermalStart = thermalStart,
            thermalEnd = thermalNow(),
            resources = ResourceSnapshot(0, 0, 0, 0, 0, 0, 0),
            errors = errors,
        )
    }

    private fun oneRun(
        label: String,
        threads: Int,
        warmup: Boolean,
        samples: FloatArray,
        sampleRate: Int,
        pcmSha256: String,
        audioMs: Long,
        initMs: Long,
        errors: MutableList<String>,
    ): OfflineRunRecord {
        val before = memoryNow()
        var peak: PeakMemory? = null
        var text = ""
        var err = ""
        val t0 = nanoTime()
        try {
            if (!inDecode.compareAndSet(false, true)) {
                overlappingRejected += 1
                throw IllegalStateException("overlapping native decode")
            }
            try {
                val result = if (peakAround != null) {
                    val pair = peakAround.invoke {
                        lastDecodeCallCount += 1
                        decodeOnce(samples, sampleRate)
                    }
                    peak = pair.second
                    pair.first
                } else {
                    lastDecodeCallCount += 1
                    decodeOnce(samples, sampleRate)
                }
                text = result
            } finally {
                inDecode.set(false)
            }
        } catch (t: Throwable) {
            err = t.message ?: t.toString()
            errors += "$label threads=$threads: $err"
        }
        val computeMs = (nanoTime() - t0) / 1_000_000L
        val after = memoryNow()
        return OfflineRunRecord(
            label = label,
            threads = threads,
            warmup = warmup,
            computeMs = computeMs,
            audioMs = audioMs,
            rtf = RealTimeFactor.compute(computeMs, audioMs),
            rawFinal = boundTranscript(text),
            recognizerInitMs = initMs,
            memoryBefore = before,
            memoryPeak = peak,
            memoryAfter = after,
            pcmSha256 = pcmSha256,
            decodeCalls = 1,
            error = err,
        )
    }
}
