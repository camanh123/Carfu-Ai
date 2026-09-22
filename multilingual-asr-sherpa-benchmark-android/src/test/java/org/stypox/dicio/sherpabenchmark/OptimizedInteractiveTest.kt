package org.stypox.dicio.sherpabenchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.stypox.dicio.sherpabenchmark.engine.AsrBackend
import org.stypox.dicio.sherpabenchmark.engine.DecodeGate
import org.stypox.dicio.sherpabenchmark.engine.InteractiveDecodeMode
import org.stypox.dicio.sherpabenchmark.engine.InteractiveOptimizeLimits
import org.stypox.dicio.sherpabenchmark.engine.OptimizedPartialPolicy
import org.stypox.dicio.sherpabenchmark.engine.PartialScheduleDecision
import org.stypox.dicio.sherpabenchmark.engine.RecognizerReuse
import org.stypox.dicio.sherpabenchmark.engine.SimulatedStreamingDecoder
import org.stypox.dicio.sherpabenchmark.engine.ThreadBenchmarkRunner
import org.stypox.dicio.sherpabenchmark.freeze.FixedAudioLimits
import org.stypox.dicio.sherpabenchmark.freeze.HardFreeze
import org.stypox.dicio.sherpabenchmark.metrics.MemorySnapshot
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class OptimizedSchedulerTest {
    @Test
    fun firstPartialWaitsThenRequests() {
        val p = OptimizedPartialPolicy()
        assertEquals(
            PartialScheduleDecision.SKIP_TOO_EARLY,
            p.decide(audioMs = 400, currentSamples = 6400, inFlight = false, pending = false),
        )
        assertEquals(
            PartialScheduleDecision.SKIP_BUSY,
            p.decide(audioMs = 600, currentSamples = 9600, inFlight = true, pending = false),
        )
        assertEquals(
            PartialScheduleDecision.REQUEST,
            p.decide(audioMs = 600, currentSamples = 9600, inFlight = false, pending = false),
        )
        assertEquals(1, p.requested.get())
        assertEquals(1, p.skippedBusy.get())
    }

    @Test
    fun layoutKeepsFailedOptimizedAndFourThreads() {
        val xml = File("src/main/res/layout/activity_main.xml").readText()
        assertTrue(xml.contains("android:id=\"@+id/modeOptimized\""))
        assertTrue(xml.contains("android:text=\"OPTIMIZED_3B1_3\""))
        assertTrue(xml.contains("android:id=\"@+id/threads4\""))
        assertTrue(Regex("""android:id="@\+id/threads4"[\s\S]{0,400}android:checked="true"""").containsMatchIn(xml))
        assertFalse(Regex("""android:id="@\+id/modeOptimized"[\s\S]{0,200}android:checked="true"""").containsMatchIn(xml))
        assertEquals(InteractiveDecodeMode.BOUNDED, InteractiveDecodeMode.DEFAULT)
        assertEquals("OPTIMIZED_3B1_3", InteractiveDecodeMode.OPTIMIZED.reportName)
    }

    @Test
    fun pendingAtMostOneAndCoalesceThenSkipBusy() {
        val p = OptimizedPartialPolicy()
        p.onPartialExecuted(decodedSamples = 9600, decodeMs = 400)
        val request = p.decide(
            audioMs = 1400,
            currentSamples = 9600 + p.adaptiveMinNewSamples(400),
            inFlight = false,
            pending = false,
        )
        assertEquals(PartialScheduleDecision.REQUEST, request)
        val coalesce = p.decide(
            audioMs = 1600,
            currentSamples = 20_000,
            inFlight = true,
            pending = false,
        )
        assertEquals(PartialScheduleDecision.COALESCE, coalesce)
        assertEquals(1, p.coalesced.get())
        val skip = p.decide(
            audioMs = 1800,
            currentSamples = 24_000,
            inFlight = true,
            pending = true,
        )
        assertEquals(PartialScheduleDecision.SKIP_BUSY, skip)
        assertEquals(1, p.skippedBusy.get())
        assertEquals(1, p.coalesced.get())
    }

    @Test
    fun skipWhenNotEnoughNewAudio() {
        val p = OptimizedPartialPolicy()
        p.onPartialExecuted(decodedSamples = 9600, decodeMs = 400)
        val d = p.decide(audioMs = 650, currentSamples = 9700, inFlight = false, pending = false)
        assertEquals(PartialScheduleDecision.SKIP_NO_NEW_AUDIO, d)
    }

    @Test
    fun stopDiscardsFurtherPartials() {
        val p = OptimizedPartialPolicy()
        p.markStop()
        assertEquals(
            PartialScheduleDecision.SKIP_BUSY,
            p.decide(audioMs = 2000, currentSamples = 32_000, inFlight = false, pending = false),
        )
        p.onFinalExecuted()
        assertEquals(1, p.finalDecodeCount.get())
    }

    @Test
    fun adaptiveWaitUsesLastDecodeDuration() {
        val p = OptimizedPartialPolicy()
        assertEquals(3200, p.adaptiveMinNewSamples(200))
        assertEquals(6400, p.adaptiveMinNewSamples(400))
        assertEquals(12_800, p.adaptiveMinNewSamples(800))
        assertEquals(12_800, p.adaptiveMinNewSamples(5000))
    }
}

class DecodeGateOptimizedStopTest {
    @Test
    fun noOverlapPendingAtMostOneDiscardPending() {
        val gate = DecodeGate()
        val inflight = AtomicInteger(0)
        val max = AtomicInteger(0)
        val ran = AtomicInteger(0)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val work: () -> Unit = {
            inflight.incrementAndGet()
            max.updateAndGet { cur -> maxOf(cur, inflight.get()) }
            Thread.sleep(40)
            ran.incrementAndGet()
            inflight.decrementAndGet()
        }
        repeat(12) { gate.trySchedule({ executor.execute(it) }, work) }
        assertTrue(gate.pendingCount <= 1)
        assertTrue(gate.inFlightCount <= 1)
        gate.discardPending()
        executor.shutdown()
        executor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(0, gate.pendingCount)
        assertEquals(1, max.get())
        assertTrue(ran.get() < 12)
        assertTrue(ran.get() >= 1)
    }
}

class FinalUsesCompletePcmTest {
    @Test
    fun exactlyOneFinalOnCompleteArrayAndPartialsNotConcatenated() {
        val seen = ArrayList<Int>()
        val texts = ArrayDeque(listOf("AAA BBB", "AAA", "FINAL RAW"))
        val backend = AsrBackend { samples, _ ->
            seen += samples.size
            texts.removeFirst()
        }
        val dec = SimulatedStreamingDecoder(backend, minNewSamples = 1, nanoTime = { System.nanoTime() })
        val a = dec.tryPartial(FloatArray(16), 200)!!
        val b = dec.tryPartial(FloatArray(32), 400)!!
        assertEquals("AAA BBB", a.text)
        assertEquals("AAA", b.text)
        assertFalse(b.text.contains("BBB"))
        val complete = FloatArray(48)
        val fin = dec.finalize(complete)
        assertEquals("FINAL RAW", fin.text)
        assertNotEquals(a.text, fin.text)
        assertEquals(48, seen.last())
        assertEquals(complete.size, dec.decodedSampleCount)
        assertEquals(3, dec.decodeAttempts)
    }
}

class LegacyPathAndFreezeTest {
    @Test
    fun legacyDecoderStillUses200msMinNewSamplesAndChunkConstant() {
        assertEquals(200L, HardFreeze.SIMULATED_STREAMING_CHUNK_MS)
        var calls = 0
        val backend = AsrBackend { _, _ ->
            calls += 1
            "x"
        }
        val legacy = SimulatedStreamingDecoder(backend)
        assertEquals(null, legacy.tryPartial(FloatArray(100), 10))
        assertEquals(0, calls)
        assertEquals(InteractiveDecodeMode.BOUNDED, InteractiveDecodeMode.DEFAULT)
        assertEquals("OPTIMIZED_3B1_3", InteractiveDecodeMode.OPTIMIZED.reportName)
        assertEquals(600L, InteractiveOptimizeLimits.FIRST_PARTIAL_AUDIO_MS)
        val src = File("src/main/java/org/stypox/dicio/sherpabenchmark/MainActivity.kt").readText()
        assertTrue(src.contains("scheduleLegacyPartial()"))
        assertTrue(src.contains("HardFreeze.SIMULATED_STREAMING_CHUNK_MS"))
        assertTrue(src.contains("SimulatedStreamingDecoder(backend)"))
        assertFalse(HardFreeze.HOTWORDS_CONNECTED)
        assertFalse(HardFreeze.PHASE2A_CONNECTED)
        assertFalse(HardFreeze.NLU_CONNECTED)
        assertFalse(HardFreeze.PRODUCTION_CONNECTED)
        val lock = File("deps.lock").readText()
        assertTrue(lock.contains("8ef5286dd427eb108055c2ddc1982aa31e544706072d5ea228729292dacade68"))
        assertTrue(lock.contains("SHERPA_ONNX_TAG=v1.13.8"))
    }

    @Test
    fun recognizerStaysPersistentAcrossSameThreadCommands() {
        assertFalse(RecognizerReuse.needsRecreate(ready = true, loadedThreads = 4, requestedThreads = 4))
        assertTrue(RecognizerReuse.needsRecreate(ready = true, loadedThreads = 4, requestedThreads = 2))
        assertTrue(RecognizerReuse.needsRecreate(ready = false, loadedThreads = 4, requestedThreads = 4))
    }

    @Test
    fun controlledBenchmarkAndSoakUnchanged() {
        assertEquals(listOf(1, 2, 4), FixedAudioLimits.THREAD_SEQUENCE)
        assertEquals(30, FixedAudioLimits.DEFAULT_SOAK_ITERATIONS)
        val runnerSrc = File("src/main/java/org/stypox/dicio/sherpabenchmark/engine/ThreadBenchmarkRunner.kt")
            .readText()
        assertFalse(runnerSrc.contains("tryPartial"))
        assertFalse(runnerSrc.contains("OPTIMIZED"))
        val soakSrc = File("src/main/java/org/stypox/dicio/sherpabenchmark/engine/MemorySoakRunner.kt")
            .readText()
        assertFalse(soakSrc.contains("tryPartial"))
        dummyMem()
        ThreadBenchmarkRunner(
            decodeOnce = { _, _ -> "ok" },
            ensureThreads = { 0L },
            memoryNow = { dummyMem() },
        )
    }

    @Test
    fun rawUnmodifiedAndNoWinnerClaim() {
        val backend = AsrBackend { _, _ -> "  RAW SMARTU  " }
        val dec = SimulatedStreamingDecoder(backend, minNewSamples = 1, nanoTime = { 1L })
        assertEquals("  RAW SMARTU  ", dec.tryPartial(FloatArray(8), 100)!!.text)
        assertEquals("  RAW SMARTU  ", dec.finalize(FloatArray(8)).text)
        val report = File("src/main/java/org/stypox/dicio/sherpabenchmark/engine/OptimizedPartialPolicy.kt")
            .readText()
        assertFalse(report.contains("BEST"))
        assertFalse(report.contains("WINNER"))
    }
}

private fun dummyMem() = MemorySnapshot(1, 2, 3, 4, 5, 6, 7, 8, false)
