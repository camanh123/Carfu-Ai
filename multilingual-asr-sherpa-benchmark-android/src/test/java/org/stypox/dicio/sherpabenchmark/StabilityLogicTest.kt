package org.stypox.dicio.sherpabenchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.stypox.dicio.sherpabenchmark.diagnostics.JournalSnapshot
import org.stypox.dicio.sherpabenchmark.diagnostics.SessionJournal
import org.stypox.dicio.sherpabenchmark.engine.AsrBackend
import org.stypox.dicio.sherpabenchmark.engine.BenchState
import org.stypox.dicio.sherpabenchmark.engine.DecodeGate
import org.stypox.dicio.sherpabenchmark.engine.FinalizeGuard
import org.stypox.dicio.sherpabenchmark.engine.PartialEvent
import org.stypox.dicio.sherpabenchmark.engine.RecordingPolicy
import org.stypox.dicio.sherpabenchmark.engine.SessionMachine
import org.stypox.dicio.sherpabenchmark.engine.SimulatedStreamingDecoder
import org.stypox.dicio.sherpabenchmark.freeze.BenchmarkLimits
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AutoStopPolicyTest {
    @Test
    fun fifteenSecondAutoStop() {
        assertEquals(15_000L, BenchmarkLimits.MAX_RECORDING_MS)
        assertEquals("BENCHMARK_MAX_DURATION", BenchmarkLimits.AUTO_STOP_REASON)
        assertFalse(RecordingPolicy.shouldAutoStop(14_999))
        assertTrue(RecordingPolicy.shouldAutoStop(15_000))
        assertTrue(RecordingPolicy.shouldAutoStop(36_480))
        assertEquals("BENCHMARK_MAX_DURATION", RecordingPolicy.autoStopReason())
    }

    @Test
    fun manualStopBeforeAutoStopDoesNotUseAutoReason() {
        assertFalse(RecordingPolicy.shouldAutoStop(4_000))
        assertEquals("MANUAL_STOP", BenchmarkLimits.MANUAL_STOP_REASON)
        assertNotEquals(BenchmarkLimits.MANUAL_STOP_REASON, BenchmarkLimits.AUTO_STOP_REASON)
    }
}

class FinalizeOnceTest {
    @Test
    fun exactlyOnceFinalizationAndNoDoubleRelease() {
        val g = FinalizeGuard()
        assertTrue(g.tryBegin())
        assertFalse(g.tryBegin())
        assertFalse(g.tryBegin())
        assertTrue(g.tryReleaseOnce())
        assertFalse(g.tryReleaseOnce())
        g.markFinished()
        assertTrue(g.hasFinished)
        g.reset()
        assertTrue(g.tryBegin())
        assertTrue(g.tryReleaseOnce())
    }
}

class DecodeBackpressureTest {
    @Test
    fun noOverlappingDecodeAndBoundedBacklog() {
        val gate = DecodeGate()
        val inflight = AtomicInteger(0)
        val maxInflight = AtomicInteger(0)
        val decodeCount = AtomicInteger(0)
        val latch = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val slow: () -> Unit = {
            val n = inflight.incrementAndGet()
            maxInflight.updateAndGet { cur -> maxOf(cur, n) }
            try {
                Thread.sleep(80)
                decodeCount.incrementAndGet()
            } finally {
                inflight.decrementAndGet()
            }
        }
        repeat(40) {
            gate.trySchedule({ executor.execute(it) }, slow)
        }
        assertTrue("pending must stay 0 or 1", gate.pendingCount <= BenchmarkLimits.MAX_DECODE_PENDING)
        assertTrue(gate.queuedRunnableCount <= 2)
        executor.execute { latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
        assertEquals(0, inflight.get())
        assertEquals(1, maxInflight.get())
        assertTrue("some ticks must coalesce", gate.coalescedCount > 0)
        assertTrue(decodeCount.get() < 40)
        assertTrue(decodeCount.get() >= 1)
    }
}

class PartialRevisionTest {
    @Test
    fun shrinkingPartialAllowedAndFinalIndependent() {
        val texts = ArrayDeque(
            listOf(
                "MỞ ĐỪNG XA EM ĐÊM NAY TRÊN SMARTTUBE",
                "MỞ ĐỪNG XA EM ĐÊM NAY TRÊN SMARTU",
                "FINAL RAW FROM RECOGNIZER",
            ),
        )
        val backend = AsrBackend { _, _ -> texts.removeFirst() }
        val dec = SimulatedStreamingDecoder(backend, minNewSamples = 1, nanoTime = {
            System.nanoTime()
        })
        val a = dec.tryPartial(FloatArray(16), 200)!!
        val b = dec.tryPartial(FloatArray(32), 400)!!
        assertEquals("MỞ ĐỪNG XA EM ĐÊM NAY TRÊN SMARTTUBE", a.text)
        assertEquals("MỞ ĐỪNG XA EM ĐÊM NAY TRÊN SMARTU", b.text)
        assertTrue("shrinking RAW partial is allowed", b.text.length < a.text.length)
        assertFalse(b.text.contains("SMARTTUBE") && b.text.endsWith("SMARTTUBE"))
        val fin = dec.finalize(FloatArray(48))
        assertEquals("FINAL RAW FROM RECOGNIZER", fin.text)
        assertNotEquals(a.text, fin.text)
        assertNotEquals(b.text, fin.text)
    }

    @Test
    fun replaceLatestDoesNotConcatenate() {
        val session = org.stypox.dicio.sherpabenchmark.engine.IsolatedSession(
            "s", 1, 1, 0, 0,
        )
        session.replaceLatestPartial(PartialEvent(1, 10, 1, "AAA BBB", 1, 10))
        session.replaceLatestPartial(PartialEvent(2, 20, 2, "AAA", 1, 20))
        assertEquals("AAA", session.latestPartial)
        assertFalse(session.latestPartial.contains("BBB"))
    }
}

class RepeatedSessionAndLifecycleTest {
    @Test
    fun repeatedStartStopAndCleanup() {
        val m = SessionMachine()
        val g = FinalizeGuard()
        repeat(4) { i ->
            g.reset()
            assertTrue(g.tryBegin())
            val s = m.start(1)
            m.requestStop()
            m.complete("final-$i", 1, 1000, 1)
            assertTrue(g.tryReleaseOnce())
            g.markFinished()
            assertEquals(BenchState.COMPLETE, m.state)
            assertEquals("final-$i", s.finalRawTranscript)
        }
        m.cleanup()
        assertEquals(BenchState.IDLE, m.state)
        val g2 = FinalizeGuard()
        assertTrue(g2.tryBegin())
        m.start(2)
        assertEquals(BenchState.RECORDING, m.state)
    }
}

class JournalSurvivalTest {
    @Test
    fun boundedJournalAndAbnormalRelaunch() {
        val dir = File.createTempFile("journal", "dir").apply {
            delete()
            mkdirs()
        }
        try {
            val j1 = SessionJournal(dir)
            j1.onLaunch()
            assertFalse(j1.previousEndedAbnormally)
            j1.beginSession("sess-crash", 123L)
            repeat(50) { n ->
                j1.update {
                    it.copy(
                        audioDurationMs = n * 200L,
                        chunkCount = n.toLong(),
                        decodeCount = n,
                        lastSuccessfulPartial = "partial-text-$n-" + "x".repeat(200),
                    )
                }
            }
            val current = File(dir, SessionJournal.CURRENT_NAME)
            assertTrue(current.isFile)
            assertTrue("journal must stay bounded", current.length() < 8_000)

            val j2 = SessionJournal(dir)
            val last = j2.onLaunch()
            assertTrue(j2.previousEndedAbnormally)
            assertEquals("sess-crash", last!!.sessionId)
            assertTrue(last.endedAbnormally)
            assertFalse(last.inProgress)
            val rendered = j2.renderLast()
            assertTrue(rendered.contains("ENDED_ABNORMALLY: YES"))
            assertTrue(rendered.contains("sess-crash"))

            j2.beginSession("sess-ok", 456L)
            j2.completeNormally()
            val j3 = SessionJournal(dir)
            j3.onLaunch()
            assertFalse(j3.previousEndedAbnormally)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun crashRecordSurvivesRelaunch() {
        val dir = File.createTempFile("journalc", "dir").apply {
            delete()
            mkdirs()
        }
        try {
            val j1 = SessionJournal(dir)
            j1.onLaunch()
            j1.beginSession("s1", 1)
            j1.recordCrash("sherpa-benchmark-worker", IllegalStateException("boom"))
            val j2 = SessionJournal(dir)
            j2.onLaunch()
            assertTrue(j2.previousEndedAbnormally)
            assertEquals("java.lang.IllegalStateException", j2.lastSessionJournal!!.exceptionClass)
            assertTrue(j2.renderLast().contains("boom"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun journalNeverStoresAudio() {
        val encoded = SessionJournal.encode(
            JournalSnapshot(lastSuccessfulPartial = "text only", sessionId = "s"),
        )
        assertFalse(encoded.contains("pcm"))
        assertFalse(encoded.contains("FloatArray"))
        assertFalse(encoded.contains("microphone"))
    }
}

class PartialHistoryBoundTest {
    @Test
    fun decoderPartialsBounded() {
        var i = 0
        val backend = AsrBackend { _, _ -> "p${i++}" }
        val dec = SimulatedStreamingDecoder(backend, minNewSamples = 1, nanoTime = { 1L })
        repeat(50) { n ->
            dec.tryPartial(FloatArray((n + 1) * 16), (n * 200).toLong())
        }
        assertEquals(50, dec.partialCount)
        assertTrue(dec.partialHistory.size <= BenchmarkLimits.MAX_PARTIAL_EVENTS)
    }
}
