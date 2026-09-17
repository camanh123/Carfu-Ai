package org.stypox.dicio.asrbenchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.stypox.dicio.asrbenchmark.audio.Pcm16kMonoRecorder
import org.stypox.dicio.asrbenchmark.config.ContextPrompt
import org.stypox.dicio.asrbenchmark.config.LanguageMode
import org.stypox.dicio.asrbenchmark.config.ThreadOption
import org.stypox.dicio.asrbenchmark.corpus.SpokenTestTargets
import org.stypox.dicio.asrbenchmark.metrics.MemorySnapshot
import org.stypox.dicio.asrbenchmark.metrics.PeakMemory
import org.stypox.dicio.asrbenchmark.metrics.RealTimeFactor
import org.stypox.dicio.asrbenchmark.report.BenchmarkSession
import org.stypox.dicio.asrbenchmark.report.DiagnosticReport
import org.stypox.dicio.asrbenchmark.report.SessionHistory
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RealTimeFactorTest {
    @Test
    fun specExamples() {
        assertEquals(1.0, RealTimeFactor.compute(5000, 5000)!!, 1e-9)
        assertEquals(0.5, RealTimeFactor.compute(2500, 5000)!!, 1e-9)
        assertEquals(2.0, RealTimeFactor.compute(10_000, 5000)!!, 1e-9)
    }

    @Test
    fun invalidDurationIsNull() {
        assertNull(RealTimeFactor.compute(1000, 0))
        assertNull(RealTimeFactor.compute(1000, -5))
        assertNull(RealTimeFactor.compute(-1, 1000))
    }

    @Test
    fun format() {
        assertEquals("n/a", RealTimeFactor.format(null))
        assertEquals("1.000", RealTimeFactor.format(1.0))
        assertEquals("0.500", RealTimeFactor.format(0.5))
    }
}

class SessionHistoryTest {
    @Test
    fun keepsTwentyNewest() {
        val h = SessionHistory(limit = 20)
        repeat(25) { i -> h.add(sampleSession(i + 1, "utt $i")) }
        assertEquals(20, h.size)
        val snap = h.snapshot()
        assertEquals(25, snap.first().sequence)
        assertEquals(6, snap.last().sequence)
    }
}

class ContextPromptTest {
    @Test
    fun vocabularyOnlyNoFullSentences() {
        val prompt = ContextPrompt.VOCABULARY_HINT
        assertTrue(prompt.contains("SmartTube"))
        assertTrue(prompt.contains("YouTube"))
        assertTrue(prompt.contains("MusicLoop"))
        assertTrue(prompt.contains("Google Maps"))
        assertTrue(prompt.contains("Vietmap"))
        ContextPrompt.forbiddenCorpusSentences().forEach { banned ->
            assertFalse("context prompt must not contain '$banned'", prompt.contains(banned))
        }
    }
}

class SpokenTestTargetsTest {
    @Test
    fun corpusIsDisplayOnly() {
        assertEquals(15, SpokenTestTargets.items.size)
        assertTrue(SpokenTestTargets.asPlainText().contains("not used for correction"))
        assertTrue(SpokenTestTargets.items.any { it.contains("SmartTube") })
        assertTrue(SpokenTestTargets.items.any { it.contains("See You Again") })
    }
}

class LanguageAndThreadsTest {
    @Test
    fun autoIsNotEnglishHardcoded() {
        assertEquals("auto", LanguageMode.AUTO.whisperLanguage)
        assertEquals("vi", LanguageMode.VI.whisperLanguage)
        assertFalse(LanguageMode.AUTO.whisperLanguage == "en")
    }

    @Test
    fun defaultFourThreads() {
        assertEquals(4, ThreadOption.DEFAULT.nThreads)
        assertEquals(2, ThreadOption.TWO.nThreads)
    }
}

class PcmConversionTest {
    @Test
    fun pcm16leToFloat() {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(32767)
        buf.putShort(-32768)
        val f = Pcm16kMonoRecorder.pcm16leToFloat32(buf.array())
        assertEquals(2, f.size)
        assertEquals(32767f / 32768f, f[0], 1e-6f)
        assertEquals(-1.0f, f[1], 1e-6f)
        assertEquals(1000L, Pcm16kMonoRecorder.durationMs(16_000))
    }
}

class DiagnosticReportTest {
    @Test
    fun copyContainsRequiredFlagsAndRawTranscript() {
        val session = sampleSession(1, "Mở See You Again trên SmartTube")
        val text = DiagnosticReport.render(
            phaseBase = PhaseInfo.PHASE_BASE,
            applicationId = "org.stypox.dicio.asrbenchmark",
            whisperSource = "https://github.com/ggml-org/whisper.cpp",
            whisperCommit = "v1.8.2 / 4979e04f",
            whisperLicense = "MIT",
            modelName = "ggml-tiny.bin",
            modelMultilingual = true,
            modelSizeBytes = 77691713L,
            modelQuantization = "none",
            modelSha256 = "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21",
            modelSource = "https://huggingface.co/ggerganov/whisper.cpp",
            minSdk = 29,
            abi = "arm64-v8a",
            nativeOptimization = "-O3",
            current = session,
            history = listOf(session),
            extraError = "",
            cpuCores = 8,
            nativeSystemInfo = "NEON = 1",
        )
        assertTrue(text.contains("RAW_OUTPUT_UNMODIFIED: YES"))
        assertTrue(text.contains("PHASE2A_CONNECTED: NO"))
        assertTrue(text.contains("NLU_CONNECTED: NO"))
        assertTrue(text.contains("PRODUCTION_CONNECTED: NO"))
        assertTrue(text.contains("RAW TRANSCRIPT: Mở See You Again trên SmartTube"))
        assertTrue(text.contains("CONTEXT: OFF"))
        assertTrue(text.contains("LANGUAGE MODE: AUTO / multilingual"))
        assertFalse(text.contains("Phase 2A Contextual Mixed-Language Refiner"))
    }
}

private fun sampleSession(seq: Int, raw: String) = BenchmarkSession(
    sequence = seq,
    timestampEpochMs = 0L,
    engine = "whisper.cpp",
    model = "ggml-tiny.bin",
    modelSizeBytes = 77691713L,
    modelSha256 = "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21",
    languageMode = LanguageMode.AUTO,
    contextOn = false,
    contextPrompt = ContextPrompt.VOCABULARY_HINT,
    nThreads = 4,
    cpuCores = 8,
    nativeSystemInfo = "test",
    audioDurationMs = 5000,
    modelLoadTimeMs = 100,
    transcriptionTimeMs = 2500,
    rtf = 0.5,
    rawTranscript = raw,
    detectedLanguage = "vi",
    languageConfidence = 0.4f,
    segmentCount = 1,
    memoryBefore = MemorySnapshot(1, 2, 3, 4, 5, 6, 7, 8, false),
    memoryAfter = MemorySnapshot(1, 2, 3, 4, 5, 6, 7, 8, false),
    peakMemory = PeakMemory(1, 2, 3),
    error = "",
    recordingStatus = "ok",
)
