package org.stypox.dicio.sherpabenchmark.engine

import org.stypox.dicio.sherpabenchmark.freeze.FixedAudioLimits
import org.stypox.dicio.sherpabenchmark.metrics.MemorySnapshot
import org.stypox.dicio.sherpabenchmark.metrics.RealTimeFactor

/**
 * Sequential memory soak on identical PCM. One offline FINAL per iteration.
 * Recognizer is reused (persists) across iterations unless [ensureThreads] recreates it.
 */
class MemorySoakRunner(
    private val decodeOnce: (FloatArray, Int) -> String,
    private val ensureThreads: (Int) -> Long,
    private val memoryNow: () -> MemorySnapshot?,
    private val countersNow: () -> ResourceSnapshot,
    private val nanoTime: () -> Long = { System.nanoTime() },
    private val wallClock: () -> Long = { System.currentTimeMillis() },
) {
    var lastDecodeCallCount: Int = 0
        private set
    private val inDecode = java.util.concurrent.atomic.AtomicBoolean(false)

    fun run(
        samples: FloatArray,
        sampleRate: Int,
        pcmSha256: String,
        audioMs: Long,
        threads: Int,
        iterations: Int,
        cancelled: () -> Boolean,
        progress: (completed: Int, total: Int, label: String) -> Unit,
        thermalStart: String,
        thermalNow: () -> String,
        persist: (SoakProgress) -> Unit,
    ): SoakResult {
        require(iterations in FixedAudioLimits.SOAK_OPTIONS) {
            "soak iterations must be one of ${FixedAudioLimits.SOAK_OPTIONS}"
        }
        lastDecodeCallCount = 0
        val start = wallClock()
        val errors = ArrayList<String>()
        val startMem = memoryNow()
        ensureThreads(threads)
        val warmup = oneIteration(
            iteration = 0,
            warmup = true,
            samples = samples,
            sampleRate = sampleRate,
            audioMs = audioMs,
            errors = errors,
        )
        val afterWarmup = memoryNow()
        persist(SoakProgress(threads, iterations, 0, inProgress = true, cancelled = false))
        val rows = ArrayList<SoakIterationRecord>(iterations)
        for (i in 1..iterations) {
            if (cancelled()) break
            progress(i, iterations, "MEMORY SOAK iteration $i / $iterations")
            rows += oneIteration(
                iteration = i,
                warmup = false,
                samples = samples,
                sampleRate = sampleRate,
                audioMs = audioMs,
                errors = errors,
            )
            persist(SoakProgress(threads, iterations, i, inProgress = true, cancelled = false))
        }
        val endMem = memoryNow()
        val nativeByIter = rows.associate { it.iteration to it.nativeHeapBytes }
        val deltas = SoakDeltas.compute(nativeByIter, iterations)
        val afterWarmupNative = afterWarmup?.nativeHeapAllocatedBytes ?: warmup.nativeHeapBytes
        val trend = MemoryTrendHeuristic.classify(afterWarmupNative, deltas)
        val nativeValues = rows.map { it.nativeHeapBytes }
        val pssValues = rows.map { it.pssKb }
        val javaValues = rows.map { it.javaUsedBytes }
        val availValues = rows.map { it.availMemBytes }
        return SoakResult(
            threads = threads,
            iterations = iterations,
            pcmSha256 = pcmSha256,
            audioMs = audioMs,
            warmup = warmup,
            rows = rows,
            nativeHeapStart = startMem?.nativeHeapAllocatedBytes ?: -1L,
            nativeHeapAfterWarmup = afterWarmupNative,
            nativeHeapMax = nativeValues.maxOrNull() ?: -1L,
            nativeHeapFinal = endMem?.nativeHeapAllocatedBytes ?: rows.lastOrNull()?.nativeHeapBytes ?: -1L,
            pssStartKb = startMem?.pssKb ?: -1L,
            pssMaxKb = pssValues.maxOrNull() ?: -1L,
            pssFinalKb = endMem?.pssKb ?: rows.lastOrNull()?.pssKb ?: -1L,
            javaHeapStart = startMem?.javaUsedBytes ?: -1L,
            javaHeapMax = javaValues.maxOrNull() ?: -1L,
            javaHeapFinal = endMem?.javaUsedBytes ?: rows.lastOrNull()?.javaUsedBytes ?: -1L,
            availMemStart = startMem?.availMemBytes ?: -1L,
            availMemMin = availValues.minOrNull() ?: -1L,
            availMemFinal = endMem?.availMemBytes ?: rows.lastOrNull()?.availMemBytes ?: -1L,
            deltas = deltas,
            trend = trend,
            cancelled = cancelled(),
            startEpochMs = start,
            durationMs = wallClock() - start,
            thermalStart = thermalStart,
            thermalEnd = thermalNow(),
            resources = countersNow(),
            recognizerPersistsAcrossIterations = true,
            errors = errors,
        )
    }

    private fun oneIteration(
        iteration: Int,
        warmup: Boolean,
        samples: FloatArray,
        sampleRate: Int,
        audioMs: Long,
        errors: MutableList<String>,
    ): SoakIterationRecord {
        var text = ""
        var err = ""
        val t0 = nanoTime()
        try {
            if (!inDecode.compareAndSet(false, true)) {
                throw IllegalStateException("overlapping native decode")
            }
            try {
                lastDecodeCallCount += 1
                text = decodeOnce(samples, sampleRate)
            } finally {
                inDecode.set(false)
            }
        } catch (t: Throwable) {
            err = t.message ?: t.toString()
            errors += "iter=$iteration: $err"
        }
        val computeMs = (nanoTime() - t0) / 1_000_000L
        val mem = memoryNow()
        val ctr = countersNow()
        return SoakIterationRecord(
            iteration = iteration,
            warmup = warmup,
            javaUsedBytes = mem?.javaUsedBytes ?: -1L,
            javaTotalBytes = mem?.javaTotalBytes ?: -1L,
            nativeHeapBytes = mem?.nativeHeapAllocatedBytes ?: -1L,
            pssKb = mem?.pssKb ?: -1L,
            availMemBytes = mem?.availMemBytes ?: -1L,
            lowMemory = mem?.lowMemory ?: false,
            recognizerInitCount = ctr.recognizerCreated,
            streamCreateCount = ctr.streamCreated,
            streamReleaseCount = ctr.streamReleased,
            computeMs = computeMs,
            rtf = RealTimeFactor.compute(computeMs, audioMs),
            rawFinal = boundTranscript(text),
            error = err,
        )
    }
}

data class SoakProgress(
    val threads: Int,
    val plannedIterations: Int,
    val lastCompletedIteration: Int,
    val inProgress: Boolean,
    val cancelled: Boolean,
)
