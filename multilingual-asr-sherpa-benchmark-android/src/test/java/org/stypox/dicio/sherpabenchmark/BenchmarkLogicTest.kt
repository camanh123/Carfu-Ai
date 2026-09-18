package org.stypox.dicio.sherpabenchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.stypox.dicio.sherpabenchmark.audio.Pcm16kMonoRecorder
import org.stypox.dicio.sherpabenchmark.config.ThreadOption
import org.stypox.dicio.sherpabenchmark.corpus.SpokenTestTargets
import org.stypox.dicio.sherpabenchmark.engine.AsrBackend
import org.stypox.dicio.sherpabenchmark.engine.BenchState
import org.stypox.dicio.sherpabenchmark.engine.ModelPaths
import org.stypox.dicio.sherpabenchmark.engine.PartialEvent
import org.stypox.dicio.sherpabenchmark.engine.RecognitionConfigFactory
import org.stypox.dicio.sherpabenchmark.engine.SessionIsolationException
import org.stypox.dicio.sherpabenchmark.engine.SessionMachine
import org.stypox.dicio.sherpabenchmark.engine.SimulatedStreamingDecoder
import org.stypox.dicio.sherpabenchmark.freeze.HardFreeze
import org.stypox.dicio.sherpabenchmark.metrics.MemorySnapshot
import org.stypox.dicio.sherpabenchmark.metrics.PeakMemory
import org.stypox.dicio.sherpabenchmark.metrics.RealTimeFactor
import org.stypox.dicio.sherpabenchmark.report.BenchmarkSession
import org.stypox.dicio.sherpabenchmark.report.DiagnosticReport
import org.stypox.dicio.sherpabenchmark.report.SessionHistory
import java.io.File
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

class ThreadSelectionTest {
    @Test
    fun optionsAreOneTwoFourDefaultOne() {
        assertEquals(1, ThreadOption.DEFAULT.nThreads)
        assertEquals(1, ThreadOption.ONE.nThreads)
        assertEquals(2, ThreadOption.TWO.nThreads)
        assertEquals(4, ThreadOption.FOUR.nThreads)
        assertEquals(setOf(1, 2, 4), ThreadOption.allowed)
        assertEquals(ThreadOption.ONE, ThreadOption.fromCount(1))
        assertEquals(ThreadOption.TWO, ThreadOption.fromCount(2))
        assertEquals(ThreadOption.FOUR, ThreadOption.fromCount(4))
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsOtherThreadCounts() {
        ThreadOption.fromCount(8)
    }
}

class SessionIsolationAndTransitionsTest {
    @Test
    fun startStopIsolationAndDoubleGuards() {
        val m = SessionMachine(clock = { 1000L })
        assertEquals(BenchState.IDLE, m.state)
        val s1 = m.start(1)
        assertEquals(BenchState.RECORDING, m.state)
        assertTrue(s1.sessionId.isNotBlank())
        try {
            m.start(2)
            fail("double START must be rejected")
        } catch (_: SessionIsolationException) {
        }
        assertEquals(s1.sessionId, m.current!!.sessionId)

        m.requestStop()
        assertEquals(BenchState.PROCESSING, m.state)
        try {
            m.requestStop()
            fail("double STOP must be rejected")
        } catch (_: SessionIsolationException) {
        }

        m.complete("raw-one", computeMs = 10, audioMs = 100, stopToFinalMs = 5)
        assertEquals(BenchState.COMPLETE, m.state)
        assertEquals("raw-one", s1.finalRawTranscript)

        val s2 = m.start(4)
        assertNotEquals(s1.sessionId, s2.sessionId)
        assertEquals("", s2.latestPartial)
        assertEquals("", s2.finalRawTranscript)
        assertEquals(0, s2.partials.size)
        assertEquals("raw-one", s1.finalRawTranscript)
        assertEquals(1, s1.threadCount)
        assertEquals(4, s2.threadCount)
    }

    @Test
    fun repeatedSessionsDoNotAppend() {
        val m = SessionMachine()
        repeat(3) { i ->
            val s = m.start(1)
            s.replaceLatestPartial(
                PartialEvent(1, 10, 0, "partial-$i", 1, 10),
            )
            m.requestStop()
            m.complete("final-$i", 1, 10, 1)
            assertEquals("final-$i", s.finalRawTranscript)
            assertEquals(1, s.partials.size)
        }
        val last = m.current!!
        assertEquals("final-2", last.finalRawTranscript)
        assertFalse(last.finalRawTranscript.contains("final-0"))
        assertFalse(last.latestPartial.contains("partial-0"))
    }

    @Test
    fun resourceCleanupClearsSession() {
        val m = SessionMachine()
        val s = m.start(2)
        m.requestStop()
        m.complete("x", 1, 1, 1)
        m.cleanup()
        assertNull(m.current)
        assertEquals(BenchState.IDLE, m.state)
        assertEquals(s.sessionId, m.lastReleasedSessionId)
        val next = m.start(1)
        assertNotEquals(s.sessionId, next.sessionId)
        assertEquals(2, next.sequence)
        assertEquals("", next.latestPartial)
        assertEquals("", next.finalRawTranscript)
    }
}

class PartialAndFinalHandlingTest {
    @Test
    fun simulatedStreamingPartialTimingAndFinal() {
        val texts = ArrayDeque(listOf("", "xin chào", "xin chào SmartTube"))
        val backend = AsrBackend { _, _ -> texts.removeFirst() }
        val dec = SimulatedStreamingDecoder(
            backend = backend,
            sampleRate = 16_000,
            minNewSamples = 1,
            clock = { 42L },
            nanoTime = monotonicNano(),
        )
        val chunk = FloatArray(3200)
        assertNull(dec.tryPartial(chunk, sessionElapsedMs = 50))
        val p1 = dec.tryPartial(FloatArray(6400), sessionElapsedMs = 200)
        assertNotNull(p1)
        assertEquals("xin chào", p1!!.text)
        assertEquals(200L, p1.sessionElapsedMs)
        assertEquals(1, p1.index)
        val fin = dec.finalize(FloatArray(9600))
        assertEquals("xin chào SmartTube", fin.text)
        assertEquals(1, fin.partials.size)
        assertEquals(200L, fin.firstNonEmptyPartialElapsedMs)
        assertTrue(fin.totalComputeMs >= 0L)
    }

    @Test
    fun rawTranscriptPreservedExactly() {
        val raw = "  RỒI CŨNG HỖ TRỢ  SmartTube\n"
        val backend = AsrBackend { _, _ -> raw }
        val dec = SimulatedStreamingDecoder(backend, minNewSamples = 1, nanoTime = monotonicNano())
        val fin = dec.finalize(FloatArray(16))
        assertEquals(raw, fin.text)
    }

    @Test
    fun decoderResetIsolatesSessions() {
        val backend = AsrBackend { _, _ -> "one" }
        val dec = SimulatedStreamingDecoder(backend, minNewSamples = 1, nanoTime = monotonicNano())
        dec.tryPartial(FloatArray(16), 10)
        dec.finalize(FloatArray(16))
        dec.reset()
        assertEquals(0, dec.partialCount)
        assertNull(dec.firstNonEmptyPartialElapsedMs)
        val backend2 = AsrBackend { _, _ -> "two" }
        val dec2 = SimulatedStreamingDecoder(backend2, minNewSamples = 1, nanoTime = monotonicNano())
        val fin = dec2.finalize(FloatArray(16))
        assertEquals("two", fin.text)
        assertFalse(fin.text.contains("one"))
    }
}

class FreezeAndCorpusTest {
    @Test
    fun freezeFlags() {
        assertEquals("cpu", HardFreeze.EXECUTION_PROVIDER)
        assertEquals("greedy_search", HardFreeze.DECODING_METHOD)
        assertFalse(HardFreeze.NATIVE_STREAMING_MODEL)
        assertTrue(HardFreeze.SIMULATED_STREAMING)
        assertTrue(HardFreeze.RAW_OUTPUT_UNMODIFIED)
        assertFalse(HardFreeze.HOTWORDS_CONNECTED)
        assertFalse(HardFreeze.PHASE2A_CONNECTED)
        assertFalse(HardFreeze.NLU_CONNECTED)
        assertFalse(HardFreeze.PRODUCTION_CONNECTED)
        assertFalse(HardFreeze.VAD_CONNECTED)
        assertTrue(HardFreeze.HOTWORDS_FILE.isEmpty())
    }

    @Test
    fun configDoesNotCarryCorpusHotwordsOrAccelerators() {
        val cfg = RecognitionConfigFactory.create(
            1,
            ModelPaths("enc.onnx", "dec.onnx", "joi.onnx", "tokens.txt", "bpe.model"),
        )
        assertEquals("cpu", cfg.provider)
        assertEquals("greedy_search", cfg.decodingMethod)
        assertEquals(1, cfg.numThreads)
        assertEquals("", cfg.hotwordsFile)
        assertEquals(0.0f, cfg.hotwordsScore, 0f)
        assertEquals("", cfg.ruleFsts)
        assertEquals("", cfg.ruleFars)
        assertEquals("", cfg.hrLexicon)
        assertEquals("", cfg.hrRuleFsts)
        assertFalse(RecognitionConfigFactory.containsForbiddenProvider(cfg))
        val probe = cfg.asProbeString()
        SpokenTestTargets.allPhrases().forEach { phrase ->
            assertFalse("recognition config must not contain '$phrase'", probe.contains(phrase))
        }
        val cfg4 = RecognitionConfigFactory.create(
            4,
            ModelPaths("e", "d", "j", "t", "b"),
        )
        assertEquals(4, cfg4.numThreads)
        val cfg2 = RecognitionConfigFactory.create(2, ModelPaths("e", "d", "j", "t", "b"))
        assertEquals(2, cfg2.numThreads)
    }

    @Test
    fun corpusIsDisplayOnly() {
        assertEquals(4, SpokenTestTargets.items.size)
        assertTrue(SpokenTestTargets.asPlainText().contains("UI/instructions only"))
        assertTrue(SpokenTestTargets.asPlainText().contains("NOT passed to the ASR engine"))
        assertTrue(SpokenTestTargets.items[0].contains("Đừng Xa Em Đêm Nay"))
        assertTrue(SpokenTestTargets.items[1].contains("See You Again"))
        assertTrue(SpokenTestTargets.items[2].contains("Nơi Này Có Anh"))
        assertTrue(SpokenTestTargets.items[3].contains("Mỹ Đình"))
    }

    @Test
    fun sourceTreeHasNoProductionPhaseOrHotwordWiring() {
        val root = File("src/main/java")
        assertTrue(root.isDirectory)
        val forbidden = listOf(
            "VoiceSession",
            "org.stypox.dicio.asrbenchmark",
            "Phase2A",
            "ContextualRefiner",
            "canonical command",
            "android.speech.SpeechRecognizer",
            "createStreamWithHotwords",
            "provider = \"nnapi\"",
            "provider = \"qnn\"",
            "silero",
        )
        root.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            forbidden.forEach { token ->
                assertFalse("${file.name} must not contain $token", text.contains(token))
            }
            assertFalse(file.readText().contains("org.stypox.dicio.skills"))
        }
    }
}

class DiagnosticExportTest {
    @Test
    fun copyContainsRequiredFieldsAndRawTranscript() {
        val session = sampleSession(1, "Mở See You Again trên SmartTube")
        val text = DiagnosticReport.render(
            applicationId = PhaseInfo.APPLICATION_ID,
            sherpaSource = "https://github.com/k2-fsa/sherpa-onnx",
            sherpaVersion = "v1.13.8 / 11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf",
            sherpaLicense = "Apache-2.0",
            modelName = "sherpa-onnx-zipformer-vi-30M-int8-2026-02-09",
            modelArchitecture = "offline-transducer-zipformer",
            modelLanguage = "vi",
            modelQuantization = "official-int8-encoder-joiner",
            modelFileSizes = "encoder.int8.onnx: 27699063 bytes",
            modelFileSha256 = "encoder.int8.onnx: 8ef5286d",
            minSdk = 29,
            targetSdk = 29,
            abi = "arm64-v8a",
            deviceApi = 29,
            cpuCores = 8,
            cpuFeatures = "neon",
            threadOptions = "1, 2, 4 (default 1)",
            current = session,
            history = listOf(session),
            extraError = "",
        )
        val required = listOf(
            "=== CARFU PHASE 3B.1 SHERPA ASR DIAGNOSTIC ===",
            "PHASE_BASE: ${PhaseInfo.PHASE_BASE}",
            "MODULE: multilingual-asr-sherpa-benchmark-android",
            "APPLICATION_ID: org.stypox.dicio.sherpabenchmark",
            "SHERPA_ONNX_SOURCE:",
            "SHERPA_ONNX_VERSION_OR_COMMIT:",
            "SHERPA_ONNX_LICENSE:",
            "MODEL: sherpa-onnx-zipformer-vi-30M-int8-2026-02-09",
            "MODEL_ARCHITECTURE:",
            "MODEL_LANGUAGE:",
            "MODEL_QUANTIZATION:",
            "MODEL FILE SIZES:",
            "MODEL FILE SHA256:",
            "ANDROID_MIN_SDK: 29",
            "ANDROID_TARGET_SDK: 29",
            "TARGET_ABI: arm64-v8a",
            "DEVICE_API: 29",
            "CPU_CORES_AVAILABLE: 8",
            "EXECUTION_PROVIDER: cpu",
            "RECOGNITION_ARCHITECTURE:",
            "RECOGNITION_MODE:",
            "NATIVE_STREAMING_MODEL: NO",
            "SIMULATED_STREAMING: YES",
            "DECODING_METHOD: greedy_search",
            "AUDIO_FORMAT:",
            "THREAD_OPTIONS:",
            "RAW_OUTPUT_UNMODIFIED: YES",
            "HOTWORDS_CONNECTED: NO",
            "PHASE2A_CONNECTED: NO",
            "NLU_CONNECTED: NO",
            "PRODUCTION_CONNECTED: NO",
            "--- CURRENT SESSION ---",
            "FINAL RAW TRANSCRIPT: Mở See You Again trên SmartTube",
            "--- HISTORY ---",
        )
        required.forEach { token ->
            assertTrue("missing '$token'", text.contains(token))
        }
        assertFalse(text.contains("Phase 2A Contextual"))
        assertEquals("org.stypox.dicio.sherpabenchmark", PhaseInfo.APPLICATION_ID)
        assertNotEquals("org.stypox.dicio", PhaseInfo.APPLICATION_ID)
        assertNotEquals("org.stypox.dicio.asrbenchmark", PhaseInfo.APPLICATION_ID)
    }

    @Test
    fun historyKeepsTwentyNewest() {
        val h = SessionHistory(limit = 20)
        repeat(25) { i -> h.add(sampleSession(i + 1, "utt $i")) }
        assertEquals(20, h.size)
        val snap = h.snapshot()
        assertEquals(25, snap.first().sequence)
        assertEquals(6, snap.last().sequence)
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

class InstallManifestTest {
    @Test
    fun sourceManifestHasNoTestOnly() {
        val manifest = File("src/main/AndroidManifest.xml")
        assertTrue(manifest.isFile)
        val text = manifest.readText()
        assertFalse(text.contains("testOnly"))
        assertTrue(text.contains("org.stypox.dicio.sherpabenchmark.SherpaBenchmarkApp") || text.contains(".SherpaBenchmarkApp"))
        assertTrue(text.contains("RECORD_AUDIO"))
    }

    @Test
    fun gradleDisablesInjectedTestOnly() {
        val props = File("gradle.properties").readText()
        assertTrue(props.contains("android.injected.testOnly=false"))
        val build = File("build.gradle.kts").readText()
        assertTrue(build.contains("applicationId = \"org.stypox.dicio.sherpabenchmark\""))
        assertTrue(build.contains("minSdk = 29"))
        assertTrue(build.contains("targetSdk = 29"))
        assertTrue(build.contains("enableV1Signing = true"))
        assertTrue(build.contains("enableV2Signing = true"))
        assertTrue(build.contains("arm64-v8a"))
        assertFalse(build.contains("nnapi"))
        assertFalse(build.contains("org.stypox.dicio.asrbenchmark"))
    }
}

private fun monotonicNano(): () -> Long {
    var n = 0L
    return { n += 1_000_000L; n }
}

private fun sampleSession(seq: Int, raw: String) = BenchmarkSession(
    sessionId = "p3b1-$seq",
    sequence = seq,
    timestampEpochMs = 0L,
    engine = "sherpa-onnx",
    model = "sherpa-onnx-zipformer-vi-30M-int8-2026-02-09",
    modelFiles = "encoder.int8.onnx,decoder.onnx,joiner.int8.onnx,tokens.txt,bpe.model",
    executionProvider = "cpu",
    recognitionMode = HardFreeze.RECOGNITION_MODE,
    nativeStreamingModel = false,
    simulatedStreaming = true,
    decodingMethod = "greedy_search",
    threadCount = 1,
    cpuCores = 8,
    androidAbi = "arm64-v8a",
    androidApi = 29,
    cpuFeatures = "test",
    audioStartEpochMs = 1,
    stopEpochMs = 2,
    audioDurationMs = 5000,
    timeToFirstAudioChunkMs = 12,
    timeToFirstNonEmptyPartialMs = 250,
    partialCount = 1,
    lastPartialEpochMs = 3,
    stopToFinalMs = 40,
    totalAsrComputeMs = 2500,
    rtf = 0.5,
    recognizerInitMs = 100,
    finalRawTranscript = raw,
    latestPartial = raw,
    partials = listOf(PartialEvent(1, 250, 3, raw, 10, 1000)),
    memoryBefore = MemorySnapshot(1, 2, 3, 4, 5, 6, 7, 8, false),
    memoryDuringSampledPeak = PeakMemory(1, 2, 3),
    memoryAfter = MemorySnapshot(1, 2, 3, 4, 5, 6, 7, 8, false),
    error = "",
    recordingStatus = "ok",
)
