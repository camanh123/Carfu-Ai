package org.stypox.dicio.sherpabenchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.stypox.dicio.sherpabenchmark.engine.AsrBackend
import org.stypox.dicio.sherpabenchmark.engine.BoundedPartialLimits
import org.stypox.dicio.sherpabenchmark.engine.BoundedPartialPolicy
import org.stypox.dicio.sherpabenchmark.engine.BoundedScheduleDecision
import org.stypox.dicio.sherpabenchmark.engine.DecodeGate
import org.stypox.dicio.sherpabenchmark.engine.InteractiveDecodeMode
import org.stypox.dicio.sherpabenchmark.engine.InteractiveOptimizeLimits
import org.stypox.dicio.sherpabenchmark.engine.RecognizerReuse
import org.stypox.dicio.sherpabenchmark.engine.SimulatedStreamingDecoder
import org.stypox.dicio.sherpabenchmark.engine.ThreadBenchmarkRunner
import org.stypox.dicio.sherpabenchmark.freeze.FixedAudioLimits
import org.stypox.dicio.sherpabenchmark.freeze.HardFreeze
import org.stypox.dicio.sherpabenchmark.metrics.MemorySnapshot
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class BoundedSchedulerTest {
    private fun samples(ms: Long): Int =
        ((BoundedPartialLimits.SAMPLE_RATE_HZ.toLong() * ms) / 1000L).toInt()

    @Test
    fun firstPartialOnlyAfter700msAudio() {
        val p = BoundedPartialPolicy()
        assertEquals(
            BoundedScheduleDecision.SKIP_TOO_EARLY,
            p.decide(audioMs = 699, currentSamples = samples(699), inFlight = false, recording = true),
        )
        assertEquals(
            BoundedScheduleDecision.REQUEST,
            p.decide(audioMs = 700, currentSamples = samples(700), inFlight = false, recording = true),
        )
        assertEquals(1, p.requested.get())
    }

    @Test
    fun laterPartialRequires900msNewAudio() {
        val p = BoundedPartialPolicy()
        p.onPartialExecuted(decodedSamples = samples(700), decodeMs = 300)
        assertEquals(
            BoundedScheduleDecision.SKIP_NO_NEW_AUDIO,
            p.decide(audioMs = 1599, currentSamples = samples(1599), inFlight = false, recording = true),
        )
        assertEquals(
            BoundedScheduleDecision.REQUEST,
            p.decide(audioMs = 1600, currentSamples = samples(1600), inFlight = false, recording = true),
        )
    }

    @Test
    fun timerAloneCannotTriggerDecode() {
        val p = BoundedPartialPolicy()
        p.onPartialExecuted(decodedSamples = samples(700), decodeMs = 250)
        repeat(20) {
            val d = p.decide(
                audioMs = 700,
                currentSamples = samples(700),
                inFlight = false,
                recording = true,
            )
            assertEquals(BoundedScheduleDecision.SKIP_NO_NEW_AUDIO, d)
        }
        assertEquals(0, p.requested.get())
        assertEquals(1, p.executed.get())
    }

    @Test
    fun skipBusyDoesNotQueueWhileNativeDecodeActive() {
        val p = BoundedPartialPolicy()
        p.onPartialExecuted(decodedSamples = samples(700), decodeMs = 400)
        val busy = p.decide(
            audioMs = 2000,
            currentSamples = samples(2000),
            inFlight = true,
            recording = true,
        )
        assertEquals(BoundedScheduleDecision.SKIP_BUSY, busy)
        assertEquals(0, p.requested.get())
        assertEquals(1, p.skippedBusy.get())
    }

    @Test
    fun partialBudgetCapsAtFive() {
        val p = BoundedPartialPolicy()
        repeat(5) { i ->
            val audioMs = 700L + i * 900L
            val snapshot = samples(audioMs)
            assertEquals(
                BoundedScheduleDecision.REQUEST,
                p.decide(audioMs = audioMs, currentSamples = snapshot, inFlight = false, recording = true),
            )
            p.onPartialExecuted(decodedSamples = snapshot, decodeMs = 200)
        }
        assertEquals(5, p.executed.get())
        assertEquals(
            BoundedScheduleDecision.DROP_BUDGET,
            p.decide(audioMs = 700L + 5 * 900L, currentSamples = samples(5200), inFlight = false, recording = true),
        )
        assertEquals(1, p.droppedBudget.get())
        assertEquals(5, p.executed.get())
        assertTrue(p.executed.get() <= BoundedPartialLimits.MAX_PARTIAL_DECODES)
    }

    @Test
    fun fourToFiveSecondCommandYieldsThreeToFivePartials() {
        val p = BoundedPartialPolicy()
        var executed = 0
        for (ms in 0L..5000L step 100L) {
            val d = p.decide(ms, samples(ms), inFlight = false, recording = true)
            if (d == BoundedScheduleDecision.REQUEST) {
                p.onPartialExecuted(samples(ms), decodeMs = 200)
                executed += 1
            }
        }
        assertTrue("expected 3-5 partials for 5s, got $executed", executed in 3..5)
        assertTrue(executed <= 5)
    }

    @Test
    fun stopDropsFurtherPartialsAndNeverBlocksFinal() {
        val p = BoundedPartialPolicy()
        p.onPartialExecuted(decodedSamples = samples(700), decodeMs = 200)
        p.markStop()
        assertFalse(p.canExecutePartial())
        assertEquals(
            BoundedScheduleDecision.DROP_STOP,
            p.decide(audioMs = 3000, currentSamples = samples(3000), inFlight = false, recording = true),
        )
        p.onFinalExecuted()
        assertEquals(1, p.finalDecodeCount.get())
        assertEquals(1, p.executed.get())
    }

    @Test
    fun stopWhileNotRecordingDropsPartial() {
        val p = BoundedPartialPolicy()
        p.markStop()
        assertEquals(
            BoundedScheduleDecision.DROP_STOP,
            p.decide(audioMs = 2000, currentSamples = samples(2000), inFlight = false, recording = false),
        )
    }

    @Test
    fun partialLogRecordsSnapshotAndDelta() {
        val p = BoundedPartialPolicy()
        p.onPartialExecuted(decodedSamples = samples(700), decodeMs = 111)
        p.onPartialExecuted(decodedSamples = samples(1600), decodeMs = 222)
        val logs = p.partialLogs()
        assertEquals(2, logs.size)
        assertEquals(1, logs[0].index)
        assertEquals(700L, logs[0].audioSnapshotMs)
        assertEquals(700L, logs[0].newAudioSincePreviousMs)
        assertEquals(111L, logs[0].decodeDurationMs)
        assertEquals(2, logs[1].index)
        assertEquals(1600L, logs[1].audioSnapshotMs)
        assertEquals(900L, logs[1].newAudioSincePreviousMs)
        assertEquals(222L, logs[1].decodeDurationMs)
    }
}

class BoundedStopAndFinalTest {
    @Test
    fun stopDiscardsPendingAndFinalUsesCompletePcmOnce() {
        val gate = DecodeGate()
        val policy = BoundedPartialPolicy()
        val ran = AtomicInteger(0)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val work: () -> Unit = {
            Thread.sleep(40)
            if (policy.canExecutePartial()) ran.incrementAndGet()
        }
        gate.trySchedule({ executor.execute(it) }, work)
        gate.trySchedule({ executor.execute(it) }, work)
        assertTrue(gate.pendingCount <= 1)
        policy.markStop()
        gate.discardPending()
        assertEquals(0, gate.pendingCount)
        executor.shutdown()
        executor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(ran.get() <= 1)
        val seen = ArrayList<Int>()
        val backend = AsrBackend { samples, _ ->
            seen += samples.size
            "RAW FINAL"
        }
        val dec = SimulatedStreamingDecoder(backend, minNewSamples = 1, nanoTime = { 1L })
        val complete = FloatArray(80_000)
        val fin = dec.finalize(complete)
        policy.onFinalExecuted()
        assertEquals("RAW FINAL", fin.text)
        assertEquals(80_000, seen.single())
        assertEquals(1, policy.finalDecodeCount.get())
    }

    @Test
    fun partialsNotConcatenatedAndRawUnmodified() {
        val texts = ArrayDeque(listOf("AAA BBB", "AAA", "  RAW FINAL  "))
        val backend = AsrBackend { _, _ -> texts.removeFirst() }
        val dec = SimulatedStreamingDecoder(backend, minNewSamples = 1, nanoTime = { 1L })
        val a = dec.tryPartial(FloatArray(16), 200)!!
        val b = dec.tryPartial(FloatArray(32), 400)!!
        assertEquals("AAA BBB", a.text)
        assertEquals("AAA", b.text)
        assertFalse(b.text.contains("BBB"))
        assertEquals("  RAW FINAL  ", dec.finalize(FloatArray(48)).text)
    }
}

class BoundedFreezeAndUiTest {
    @Test
    fun defaultBoundedPreservesFailedOptimizedAndLegacy() {
        assertEquals(InteractiveDecodeMode.BOUNDED, InteractiveDecodeMode.DEFAULT)
        assertEquals("BOUNDED", InteractiveDecodeMode.BOUNDED.reportName)
        assertEquals("OPTIMIZED_3B1_3", InteractiveDecodeMode.OPTIMIZED.reportName)
        assertEquals("LEGACY", InteractiveDecodeMode.LEGACY.reportName)
        assertEquals(600L, InteractiveOptimizeLimits.FIRST_PARTIAL_AUDIO_MS)
        assertEquals(700L, BoundedPartialLimits.FIRST_PARTIAL_AUDIO_MS)
        assertEquals(900L, BoundedPartialLimits.NEW_AUDIO_MS)
        assertEquals(5, BoundedPartialLimits.MAX_PARTIAL_DECODES)
        val xml = File("src/main/res/layout/activity_main.xml").readText()
        assertTrue(xml.contains("OPTIMIZED_3B1_3"))
        assertTrue(xml.contains("DEVICE FAIL BASELINE"))
        assertTrue(xml.contains("DEVICE BASELINE"))
        assertTrue(xml.contains("android:id=\"@+id/modeBounded\""))
        assertTrue(Regex("""android:id="@\+id/modeBounded"[\s\S]{0,400}android:checked="true"""").containsMatchIn(xml))
        assertFalse(Regex("""android:id="@\+id/modeOptimized"[\s\S]{0,200}android:checked="true"""").containsMatchIn(xml))
        val main = File("src/main/java/org/stypox/dicio/sherpabenchmark/MainActivity.kt").readText()
        assertTrue(main.contains("scheduleLegacyPartial()"))
        assertTrue(main.contains("scheduleOptimizedPartial()"))
        assertTrue(main.contains("scheduleBoundedPartial()"))
        assertTrue(main.contains("HardFreeze.SIMULATED_STREAMING_CHUNK_MS"))
        val failed = File("src/main/java/org/stypox/dicio/sherpabenchmark/engine/OptimizedPartialPolicy.kt").readText()
        assertTrue(failed.contains("firstPartialAudioMs: Long = InteractiveOptimizeLimits.FIRST_PARTIAL_AUDIO_MS"))
        assertTrue(failed.contains("InteractiveOptimizeLimits.MIN_NEW_AUDIO_MS"))
        assertFalse(failed.contains("MAX_PARTIAL_DECODES"))
        assertFalse(failed.contains("BoundedPartialLimits"))
        assertFalse(failed.contains("BEST"))
        assertFalse(failed.contains("WINNER"))
    }

    @Test
    fun recognizerPersistentBenchmarkSoakHashesUnchanged() {
        assertFalse(RecognizerReuse.needsRecreate(ready = true, loadedThreads = 4, requestedThreads = 4))
        assertTrue(RecognizerReuse.needsRecreate(ready = true, loadedThreads = 4, requestedThreads = 2))
        assertEquals(listOf(1, 2, 4), FixedAudioLimits.THREAD_SEQUENCE)
        assertEquals(30, FixedAudioLimits.DEFAULT_SOAK_ITERATIONS)
        assertEquals(200L, HardFreeze.SIMULATED_STREAMING_CHUNK_MS)
        val runnerSrc = File("src/main/java/org/stypox/dicio/sherpabenchmark/engine/ThreadBenchmarkRunner.kt").readText()
        assertFalse(runnerSrc.contains("tryPartial"))
        assertFalse(runnerSrc.contains("BOUNDED"))
        val soakSrc = File("src/main/java/org/stypox/dicio/sherpabenchmark/engine/MemorySoakRunner.kt").readText()
        assertFalse(soakSrc.contains("tryPartial"))
        ThreadBenchmarkRunner(
            decodeOnce = { _, _ -> "ok" },
            ensureThreads = { 0L },
            memoryNow = { dummyMemBounded() },
        )
        val lock = File("deps.lock").readText()
        assertTrue(lock.contains("8ef5286dd427eb108055c2ddc1982aa31e544706072d5ea228729292dacade68"))
        assertTrue(lock.contains("SHERPA_ONNX_TAG=v1.13.8"))
        assertTrue(lock.contains("11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf"))
        assertEquals("greedy_search", HardFreeze.DECODING_METHOD)
        assertFalse(HardFreeze.HOTWORDS_CONNECTED)
        assertFalse(HardFreeze.PRODUCTION_CONNECTED)
    }
}

private fun dummyMemBounded() = MemorySnapshot(1, 2, 3, 4, 5, 6, 7, 8, false)
