package org.stypox.dicio.sherpabenchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.stypox.dicio.sherpabenchmark.audio.Pcm16kMonoRecorder
import org.stypox.dicio.sherpabenchmark.audio.ReferenceAudio
import org.stypox.dicio.sherpabenchmark.audio.ReferenceAudioStore
import org.stypox.dicio.sherpabenchmark.audio.Sha256
import org.stypox.dicio.sherpabenchmark.diagnostics.BenchmarkJournal
import org.stypox.dicio.sherpabenchmark.engine.DecodeGate
import org.stypox.dicio.sherpabenchmark.engine.MemorySoakRunner
import org.stypox.dicio.sherpabenchmark.engine.MemoryTrend
import org.stypox.dicio.sherpabenchmark.engine.MemoryTrendHeuristic
import org.stypox.dicio.sherpabenchmark.engine.NamedDelta
import org.stypox.dicio.sherpabenchmark.engine.OfflineRunRecord
import org.stypox.dicio.sherpabenchmark.engine.ResourceCounters
import org.stypox.dicio.sherpabenchmark.engine.ResourceSnapshot
import org.stypox.dicio.sherpabenchmark.engine.SimulatedStreamingDecoder
import org.stypox.dicio.sherpabenchmark.engine.SoakDeltas
import org.stypox.dicio.sherpabenchmark.engine.SoakProgress
import org.stypox.dicio.sherpabenchmark.engine.Stats
import org.stypox.dicio.sherpabenchmark.engine.ThreadBenchmarkRunner
import org.stypox.dicio.sherpabenchmark.engine.boundTranscript
import org.stypox.dicio.sherpabenchmark.engine.summarizeThreadConfig
import org.stypox.dicio.sherpabenchmark.freeze.BenchmarkLimits
import org.stypox.dicio.sherpabenchmark.freeze.FixedAudioLimits
import org.stypox.dicio.sherpabenchmark.freeze.HardFreeze
import org.stypox.dicio.sherpabenchmark.metrics.MemorySnapshot
import org.stypox.dicio.sherpabenchmark.metrics.RealTimeFactor
import org.stypox.dicio.sherpabenchmark.report.ControlledBenchmarkReport
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

class ReferencePcmIdentityTest {
    @Test
    fun sha256StableAndIdenticalPcmReused() {
        val pcm = pcmOf(16_000)
        val sha1 = Sha256.hex(pcm)
        val sha2 = Sha256.hex(pcm.copyOf())
        assertEquals(sha1, sha2)
        val dir = tempDir("ref")
        try {
            val store = ReferenceAudioStore(dir)
            val saved = store.save(pcm, 1L)
            val loaded = store.load()!!
            assertEquals(saved.sha256, loaded.sha256)
            assertTrue(saved.pcm16.contentEquals(loaded.pcm16))
            val samples = loaded.toFloat32()
            val seen = ArrayList<FloatArray>()
            val runner = ThreadBenchmarkRunner(
                decodeOnce = { s, _ ->
                    seen += s
                    "RAW FINAL"
                },
                ensureThreads = { 0L },
                memoryNow = { dummyMem(1) },
            )
            runner.run(
                samples = samples,
                sampleRate = 16_000,
                pcmSha256 = loaded.sha256,
                audioMs = loaded.durationMs,
                sampleCount = loaded.sampleCount,
                byteCount = loaded.byteCount,
                cancelled = { false },
                progress = { _, _, _ -> },
                thermalStart = "unavailable",
                thermalNow = { "unavailable" },
            )
            assertTrue(seen.isNotEmpty())
            seen.forEach { arr ->
                assertTrue("same FloatArray instance", arr === samples)
                assertEquals(loaded.sha256, Sha256.hex(loaded.pcm16))
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}

class ThreadBenchmarkContractTest {
    @Test
    fun sequenceWarmupMeasuredAndMedianExcludesWarmup() {
        val threadCalls = ArrayList<Int>()
        val labels = ArrayList<String>()
        val pcm = FloatArray(160)
        val runner = ThreadBenchmarkRunner(
            decodeOnce = { _, _ -> "hello" },
            ensureThreads = { n -> threadCalls += n; 12L },
            memoryNow = { dummyMem(2) },
            nanoTime = monotonicBy(listOf(5_000L, 10L, 20L, 30L)),
        )
        val result = runner.run(
            samples = pcm,
            sampleRate = 16_000,
            pcmSha256 = "abc",
            audioMs = 1000L,
            sampleCount = 160,
            byteCount = 320,
            cancelled = { false },
            progress = { _, _, _ -> },
            thermalStart = "NONE",
            thermalNow = { "NONE" },
        )
        assertEquals(listOf(1, 2, 4), threadCalls)
        assertEquals(FixedAudioLimits.THREAD_SEQUENCE, threadCalls)
        assertEquals(3, result.configs.size)
        result.configs.forEach { cfg ->
            assertEquals(1, listOfNotNull(cfg.warmup).size)
            assertEquals(3, cfg.measured.size)
            assertTrue(cfg.warmup!!.warmup)
            cfg.measured.forEach { assertFalse(it.warmup) }
            assertEquals("abc", cfg.warmup!!.pcmSha256)
            cfg.measured.forEach { assertEquals("abc", it.pcmSha256) }
        }
        assertEquals(12, runner.lastDecodeCallCount)

        val warmup = dummyRun(9999, warmup = true)
        val m1 = dummyRun(10)
        val m2 = dummyRun(30)
        val m3 = dummyRun(20)
        val sum = summarizeThreadConfig(1, 5, warmup, listOf(m1, m2, m3))
        assertEquals(20, sum.medianComputeMs)
        assertEquals(10, sum.minComputeMs)
        assertEquals(30, sum.maxComputeMs)
        assertNotEquals(9999, sum.medianComputeMs)
        labels += "ok"
        assertEquals(1, labels.size)
    }

    @Test
    fun rtfAndNoSimulatedStreamingLoop() {
        assertEquals(0.5, RealTimeFactor.compute(500, 1000)!!, 1e-9)
        val src = File("src/main/java/org/stypox/dicio/sherpabenchmark/engine/ThreadBenchmarkRunner.kt")
            .readText()
        assertFalse(src.contains("SIMULATED_STREAMING_CHUNK_MS"))
        assertFalse(src.contains("tryPartial"))
        assertFalse(src.contains("SimulatedStreamingDecoder"))
        val soakSrc = File("src/main/java/org/stypox/dicio/sherpabenchmark/engine/MemorySoakRunner.kt")
            .readText()
        assertFalse(soakSrc.contains("tryPartial"))
        assertFalse(soakSrc.contains("postDelayed"))
    }

    @Test
    fun sequentialOnlyAndCancellation() {
        val inflight = AtomicInteger(0)
        val max = AtomicInteger(0)
        val cancelAfter = AtomicInteger(0)
        val runner = ThreadBenchmarkRunner(
            decodeOnce = { _, _ ->
                val n = inflight.incrementAndGet()
                max.updateAndGet { cur -> maxOf(cur, n) }
                try {
                    Thread.sleep(5)
                    "x"
                } finally {
                    inflight.decrementAndGet()
                    cancelAfter.incrementAndGet()
                }
            },
            ensureThreads = { 0L },
            memoryNow = { dummyMem(1) },
        )
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        runner.run(
            samples = FloatArray(8),
            sampleRate = 16_000,
            pcmSha256 = "s",
            audioMs = 100,
            sampleCount = 8,
            byteCount = 16,
            cancelled = { cancelled.get() },
            progress = { done, _, _ -> if (done >= 2) cancelled.set(true) },
            thermalStart = "unavailable",
            thermalNow = { "unavailable" },
        )
        assertEquals(1, max.get())
        assertTrue(runner.lastDecodeCallCount < 12)
        assertTrue(cancelled.get())
    }
}

class SoakAndResourceTest {
    @Test
    fun soakOptionsAndBoundedRows() {
        assertEquals(listOf(10, 30, 50), FixedAudioLimits.SOAK_OPTIONS)
        assertEquals(30, FixedAudioLimits.DEFAULT_SOAK_ITERATIONS)
        assertEquals(1, FixedAudioLimits.DEFAULT_SOAK_THREADS)
        val native = HashMap<Int, Long>()
        val runner = MemorySoakRunner(
            decodeOnce = { _, _ -> "RAW" },
            ensureThreads = { 0L },
            memoryNow = {
                dummyMem(native.size * 1000L + 50_000L)
            },
            countersNow = {
                ResourceSnapshot(1, 0, native.size, native.size, 0, 0, 0)
            },
        )
        val result = runner.run(
            samples = FloatArray(8),
            sampleRate = 16_000,
            pcmSha256 = "s",
            audioMs = 200,
            threads = 1,
            iterations = 10,
            cancelled = { false },
            progress = { _, _, _ -> },
            thermalStart = "unavailable",
            thermalNow = { "unavailable" },
            persist = { },
        )
        assertEquals(10, result.rows.size)
        assertEquals(11, runner.lastDecodeCallCount) // warmup + 10
        assertTrue(result.recognizerPersistsAcrossIterations)
        assertEquals(0, result.resources.activeStreams)
        try {
            MemorySoakRunner(
                decodeOnce = { _, _ -> "" },
                ensureThreads = { 0L },
                memoryNow = { dummyMem(1) },
                countersNow = { ResourceSnapshot(0, 0, 0, 0, 0, 0, 0) },
            ).run(
                samples = FloatArray(1),
                sampleRate = 16_000,
                pcmSha256 = "s",
                audioMs = 10,
                threads = 1,
                iterations = 7,
                cancelled = { false },
                progress = { _, _, _ -> },
                thermalStart = "unavailable",
                thermalNow = { "unavailable" },
                persist = { },
            )
            fail("7 iterations must be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun streamCreateReleaseExactlyOnceAndNoOverlap() {
        val c = ResourceCounters()
        assertTrue(c.tryBeginDecode())
        assertFalse(c.tryBeginDecode())
        c.onStreamCreated()
        assertEquals(1, c.activeStreams.get())
        c.onStreamReleased()
        assertEquals(0, c.activeStreams.get())
        c.endDecode()
        assertEquals(0, c.activeDecode.get())
        assertEquals(1, c.streamCreated.get())
        assertEquals(1, c.streamReleased.get())
        assertTrue(c.tryBeginDecode())
        c.endDecode()
    }

    @Test
    fun soakDeltasAndConservativeTrend() {
        val map = mapOf(1 to 100L, 5 to 110L, 10 to 112L, 20 to 113L, 30 to 114L)
        val d = SoakDeltas.compute(map, 30)
        assertEquals("1->5", d[0].label)
        assertEquals(listOf("1->5", "5->10", "10->20", "20->30"), d.map { it.label })
        val plateau = MemoryTrendHeuristic.classify(
            afterWarmupNative = 400L * 1024L * 1024L,
            deltas = listOf(
                NamedDelta("10->20", 1_000_000L),
                NamedDelta("20->30", 500_000L),
            ),
        )
        assertEquals(MemoryTrend.PLATEAU_LIKE, plateau)
        val growth = MemoryTrendHeuristic.classify(
            afterWarmupNative = 80L * 1024L * 1024L,
            deltas = listOf(
                NamedDelta("1->5", 40L * 1024L * 1024L),
                NamedDelta("5->10", 40L * 1024L * 1024L),
                NamedDelta("10->20", 80L * 1024L * 1024L),
                NamedDelta("20->30", 80L * 1024L * 1024L),
            ),
        )
        assertEquals(MemoryTrend.CONTINUING_GROWTH, growth)
        val mixed = MemoryTrendHeuristic.classify(
            afterWarmupNative = 80L * 1024L * 1024L,
            deltas = listOf(
                NamedDelta("1->5", 40L * 1024L * 1024L),
                NamedDelta("10->20", -1_000_000L),
            ),
        )
        assertEquals(MemoryTrend.INCONCLUSIVE, mixed)
        assertFalse(MemoryTrendHeuristic.EXPLANATION.contains("MEMORY_LEAK: YES"))
    }
}

class BenchmarkJournalAndReportTest {
    @Test
    fun abnormalJournalAndBoundedReport() {
        val dir = tempDir("bjournal")
        try {
            val j1 = BenchmarkJournal(dir)
            j1.onLaunch()
            assertFalse(j1.previousEndedAbnormally)
            j1.markProgress(SoakProgress(1, 30, 7, inProgress = true, cancelled = false))
            val j2 = BenchmarkJournal(dir)
            j2.onLaunch()
            assertTrue(j2.previousEndedAbnormally)
            assertEquals(7, j2.lastCompletedIteration)
            assertTrue(j2.renderPreviousBanner().contains("PREVIOUS BENCHMARK ENDED ABNORMALLY: YES"))
            assertTrue(j2.renderPreviousBanner().contains("LAST COMPLETED ITERATION: 7"))
            j2.complete("x".repeat(2000))
            assertTrue(File(dir, BenchmarkJournal.LAST_NAME).length() < 8_000)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun reportHasRequiredFieldsAndNoWinner() {
        val pcm = pcmOf(160)
        val ref = ReferenceAudio(pcm, sha256 = Sha256.hex(pcm), recordedEpochMs = 1)
        val text = ControlledBenchmarkReport.render(
            modelName = "sherpa-onnx-zipformer-vi-30M-int8-2026-02-09",
            modelHashes = "encoder.int8.onnx: 8ef5286dd427eb108055c2ddc1982aa31e544706072d5ea228729292dacade68",
            sherpaVersion = "v1.13.8 / 11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf",
            reference = ref,
            thread = null,
            soak = null,
            pendingDecode = 0,
            previousEndedAbnormally = false,
            lastCompletedIteration = -1,
        )
        listOf(
            "=== CARFU PHASE 3B.1.2 CONTROLLED BENCHMARK ===",
            "MODEL: sherpa-onnx-zipformer-vi-30M-int8-2026-02-09",
            "SHERPA: v1.13.8",
            "EXECUTION_PROVIDER: cpu",
            "DECODING: greedy_search",
            "INTERACTIVE_MODE: simulated-streaming-offline-transducer",
            "FIXED_AUDIO_BENCHMARK_MODE: offline-final-only",
            "HOTWORDS_CONNECTED: NO",
            "PHASE2A_CONNECTED: NO",
            "NLU_CONNECTED: NO",
            "PRODUCTION_CONNECTED: NO",
            "REFERENCE_AUDIO_SHA256:",
            "--- THREAD BENCHMARK ---",
            "--- MEMORY SOAK ---",
        ).forEach { token ->
            assertTrue("missing $token", text.contains(token))
        }
        assertFalse(text.contains("BEST"))
        assertFalse(text.contains("WINNER"))
        assertFalse(text.contains("RECOMMENDED"))
        assertFalse(text.contains("MEMORY_LEAK: YES"))
    }

    @Test
    fun rawTranscriptUnmodifiedAndInteractiveFreeze() {
        assertEquals("  Hello SMARTU  ", boundTranscript("  Hello SMARTU  "))
        val backend = org.stypox.dicio.sherpabenchmark.engine.AsrBackend { _, _ -> "  RAW  " }
        val dec = SimulatedStreamingDecoder(backend, minNewSamples = 1, nanoTime = { 1L })
        val p = dec.tryPartial(FloatArray(16), 200)!!
        assertEquals("  RAW  ", p.text)
        val fin = dec.finalize(FloatArray(32))
        assertEquals("  RAW  ", fin.text)
        assertEquals(15_000L, BenchmarkLimits.MAX_RECORDING_MS)
        assertEquals(200L, HardFreeze.SIMULATED_STREAMING_CHUNK_MS)
        assertTrue(HardFreeze.SIMULATED_STREAMING)
        assertEquals("simulated-streaming-offline-transducer", HardFreeze.INTERACTIVE_MODE)
        assertEquals("offline-final-only", HardFreeze.FIXED_AUDIO_BENCHMARK_MODE)
        val gate = DecodeGate()
        repeat(8) { gate.trySchedule({ }, { }) }
        assertTrue(gate.pendingCount <= 1)
        assertFalse(HardFreeze.HOTWORDS_CONNECTED)
        assertFalse(HardFreeze.PHASE2A_CONNECTED)
        assertFalse(HardFreeze.NLU_CONNECTED)
        assertFalse(HardFreeze.PRODUCTION_CONNECTED)
        val lock = File("deps.lock").readText()
        assertTrue(lock.contains("8ef5286dd427eb108055c2ddc1982aa31e544706072d5ea228729292dacade68"))
        assertTrue(lock.contains("SHERPA_ONNX_TAG=v1.13.8"))
        assertTrue(lock.contains("11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf"))
        assertEquals("greedy_search", HardFreeze.DECODING_METHOD)
        val interactiveDiag = File("src/main/java/org/stypox/dicio/sherpabenchmark/report/DiagnosticReport.kt")
            .readText()
        assertTrue(interactiveDiag.contains("=== CARFU PHASE 3B.1.1 SHERPA ASR DIAGNOSTIC ==="))
    }

    @Test
    fun statsMedianMinMax() {
        assertEquals(20, Stats.medianLong(listOf(10, 30, 20)))
        assertEquals(10, Stats.minLong(listOf(10, 30, 20)))
        assertEquals(30, Stats.maxLong(listOf(10, 30, 20)))
        assertEquals(0.2, Stats.medianDouble(listOf(0.1, 0.3, 0.2)), 1e-9)
    }
}

private fun dummyMem(native: Long) = MemorySnapshot(
    javaUsedBytes = 10,
    javaTotalBytes = 20,
    javaMaxBytes = 30,
    nativeHeapAllocatedBytes = native,
    pssKb = 40,
    availMemBytes = 50,
    totalMemBytes = 60,
    thresholdBytes = 1,
    lowMemory = false,
)

private fun dummyRun(compute: Long, warmup: Boolean = false) = OfflineRunRecord(
    label = if (warmup) "WARMUP" else "RUN",
    threads = 1,
    warmup = warmup,
    computeMs = compute,
    audioMs = 1000,
    rtf = RealTimeFactor.compute(compute, 1000),
    rawFinal = "x",
    recognizerInitMs = 0,
    memoryBefore = dummyMem(1),
    memoryPeak = null,
    memoryAfter = dummyMem(1),
    pcmSha256 = "abc",
    decodeCalls = 1,
)

private fun pcmOf(samples: Int): ByteArray {
    val buf = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
    repeat(samples) { i -> buf.putShort((i % 100).toShort()) }
    return buf.array()
}

private fun tempDir(prefix: String): File =
    File.createTempFile(prefix, "dir").apply {
        delete()
        mkdirs()
    }

private fun monotonicBy(perRunComputeMs: List<Long>): () -> Long {
    // each run consumes two nano reads: start then end. Cycle compute list per run.
    var t = 0L
    var i = 0
    var start = true
    return {
        if (start) {
            start = false
            t
        } else {
            val add = perRunComputeMs[i % perRunComputeMs.size] * 1_000_000L
            i += 1
            start = true
            t += add
            t
        }
    }
}
