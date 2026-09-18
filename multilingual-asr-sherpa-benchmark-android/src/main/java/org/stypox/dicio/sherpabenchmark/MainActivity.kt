package org.stypox.dicio.sherpabenchmark

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.stypox.dicio.sherpabenchmark.audio.Pcm16kMonoRecorder
import org.stypox.dicio.sherpabenchmark.config.ThreadOption
import org.stypox.dicio.sherpabenchmark.corpus.SpokenTestTargets
import org.stypox.dicio.sherpabenchmark.databinding.ActivityMainBinding
import org.stypox.dicio.sherpabenchmark.diagnostics.DeviceInfo
import org.stypox.dicio.sherpabenchmark.engine.RecognitionConfigFactory
import org.stypox.dicio.sherpabenchmark.engine.SessionIsolationException
import org.stypox.dicio.sherpabenchmark.engine.SessionMachine
import org.stypox.dicio.sherpabenchmark.engine.SherpaOfflineBackend
import org.stypox.dicio.sherpabenchmark.engine.SimulatedStreamingDecoder
import org.stypox.dicio.sherpabenchmark.freeze.HardFreeze
import org.stypox.dicio.sherpabenchmark.metrics.MemoryProbe
import org.stypox.dicio.sherpabenchmark.metrics.PeakMemory
import org.stypox.dicio.sherpabenchmark.metrics.RealTimeFactor
import org.stypox.dicio.sherpabenchmark.model.ModelInstaller
import org.stypox.dicio.sherpabenchmark.report.BenchmarkSession
import org.stypox.dicio.sherpabenchmark.report.DiagnosticReport
import org.stypox.dicio.sherpabenchmark.report.SessionHistory
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val backend = SherpaOfflineBackend()
    private val recorder = Pcm16kMonoRecorder()
    private val history = SessionHistory()
    private val sessions = SessionMachine()
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "sherpa-benchmark-worker")
    }
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var memory: MemoryProbe

    @Volatile
    private var recognizerInitMs: Long = -1L

    @Volatile
    private var lastError: String = ""

    @Volatile
    private var currentReport: BenchmarkSession? = null

    private var decoder: SimulatedStreamingDecoder? = null
    private var durationTicker: Runnable? = null
    private var peakSampler: MemoryProbe.PeakSampler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        memory = MemoryProbe(this)

        binding.corpus.text = SpokenTestTargets.asPlainText()
        refreshConfigLine()
        bindControls()
        setStateLabel("IDLE")
        loadRecognizerAsync(selectedThreads().nThreads)
    }

    private fun bindControls() {
        binding.threadGroup.setOnCheckedChangeListener { _, _ ->
            if (sessions.canStart()) {
                refreshConfigLine()
            }
        }
        binding.startStop.setOnClickListener { onStartStop() }
        binding.copyDiagnostic.setOnClickListener { copyDiagnostic() }
    }

    private fun selectedThreads(): ThreadOption = when {
        binding.threads4.isChecked -> ThreadOption.FOUR
        binding.threads2.isChecked -> ThreadOption.TWO
        else -> ThreadOption.ONE
    }

    private fun refreshConfigLine() {
        binding.configLine.text = buildString {
            append("ENGINE=${BuildConfig.ENGINE_NAME}\n")
            append("MODEL=${BuildConfig.MODEL_NAME}\n")
            append("PROVIDER=${HardFreeze.EXECUTION_PROVIDER}\n")
            append("MODE=${HardFreeze.RECOGNITION_MODE}\n")
            append("NATIVE_STREAMING=${HardFreeze.yesNo(HardFreeze.NATIVE_STREAMING_MODEL)}  ")
            append("SIMULATED_STREAMING=${HardFreeze.yesNo(HardFreeze.SIMULATED_STREAMING)}\n")
            append("THREADS=${selectedThreads().nThreads}  ABI=${PhaseInfo.ABI}  ")
            append("minSdk=${PhaseInfo.MIN_SDK} targetSdk=${PhaseInfo.TARGET_SDK}  ")
            append("API=${DeviceInfo.api()}  cores=${DeviceInfo.cpuCores()}")
        }
    }

    private fun setStateLabel(state: String) {
        binding.status.text = "STATE: $state"
    }

    private fun setError(text: String) {
        lastError = text
        binding.error.text = if (text.isBlank()) "" else "ERROR: $text"
    }

    private fun setThreadControlsEnabled(enabled: Boolean) {
        binding.threads1.isEnabled = enabled
        binding.threads2.isEnabled = enabled
        binding.threads4.isEnabled = enabled
    }

    private fun loadRecognizerAsync(threads: Int) {
        setStateLabel("IDLE")
        setError("")
        worker.execute {
            try {
                val paths = ModelInstaller.ensureInstalled(this)
                val cfg = RecognitionConfigFactory.create(threads, paths)
                recognizerInitMs = backend.init(cfg)
                ui.post {
                    setStateLabel("IDLE")
                    binding.timing.text = "MODEL / RECOGNIZER INIT TIME: ${recognizerInitMs} ms\n" +
                        "Press START. CPU only. greedy_search. simulated streaming."
                    refreshConfigLine()
                }
            } catch (t: Throwable) {
                val msg = t.message ?: t.toString()
                ui.post {
                    setStateLabel("ERROR")
                    setError(msg)
                }
            }
        }
    }

    private fun onStartStop() {
        if (recorder.isRecording) {
            stopAndFinalize()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQ_RECORD,
            )
            return
        }
        startCapture()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_RECORD && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCapture()
        } else if (requestCode == REQ_RECORD) {
            setError("RECORD_AUDIO permission denied")
            setStateLabel("ERROR")
        }
    }

    private fun startCapture() {
        if (!backend.isReady) {
            setError("recognizer is not loaded")
            setStateLabel("ERROR")
            return
        }
        val threads = selectedThreads().nThreads
        try {
            sessions.start(threads)
        } catch (e: SessionIsolationException) {
            setError(e.message ?: "double START")
            return
        }
        worker.execute {
            try {
                if (backend.loadedThreads != threads) {
                    val paths = ModelInstaller.ensureInstalled(this)
                    val cfg = RecognitionConfigFactory.create(threads, paths)
                    recognizerInitMs = backend.init(cfg)
                }
                decoder = SimulatedStreamingDecoder(backend)
                recorder.start()
                peakSampler = memory.startPeakSampler()
                ui.post {
                    binding.startStop.text = "STOP"
                    setThreadControlsEnabled(false)
                    setError("")
                    binding.latestPartial.text = "(none)"
                    binding.finalTranscript.text = "(recording…)"
                    sessions.current?.recognizerInitMs = recognizerInitMs
                    setStateLabel("RECORDING")
                    startTickers()
                }
            } catch (t: Throwable) {
                sessions.fail(t.message ?: t.toString())
                ui.post {
                    binding.startStop.text = "START"
                    setThreadControlsEnabled(true)
                    setStateLabel("ERROR")
                    setError(t.message ?: t.toString())
                }
            }
        }
    }

    private fun startTickers() {
        stopTickers()
        val r = object : Runnable {
            override fun run() {
                if (!recorder.isRecording) return
                val ms = recorder.recordedDurationMs()
                setStateLabel("RECORDING  AUDIO DURATION=${ms} ms")
                worker.execute { maybePartial() }
                ui.postDelayed(this, HardFreeze.SIMULATED_STREAMING_CHUNK_MS)
            }
        }
        durationTicker = r
        ui.post(r)
    }

    private fun stopTickers() {
        durationTicker?.let { ui.removeCallbacks(it) }
        durationTicker = null
    }

    private fun maybePartial() {
        val session = sessions.current ?: return
        val dec = decoder ?: return
        if (!recorder.isRecording) return
        val samples = recorder.snapshotFloatSamples()
        val elapsed = recorder.recordedDurationMs()
        val event = dec.tryPartial(samples, elapsed) ?: return
        session.replaceLatestPartial(event)
        ui.post {
            binding.latestPartial.text = event.text
        }
    }

    private fun stopAndFinalize() {
        stopTickers()
        binding.startStop.isEnabled = false
        setStateLabel("PROCESSING")
        val stopRequestedAt = System.currentTimeMillis()
        worker.execute {
            val session = try {
                sessions.requestStop()
            } catch (e: SessionIsolationException) {
                ui.post {
                    binding.startStop.isEnabled = true
                    setError(e.message ?: "double STOP")
                }
                return@execute
            }
            val before = memory.snapshot()
            val samples = try {
                recorder.stopAndFloatSamples()
            } catch (t: Throwable) {
                sessions.fail(t.message ?: t.toString())
                ui.post {
                    binding.startStop.isEnabled = true
                    binding.startStop.text = "START"
                    setThreadControlsEnabled(true)
                    setStateLabel("ERROR")
                    setError(t.message ?: t.toString())
                }
                return@execute
            }
            val audioMs = Pcm16kMonoRecorder.durationMs(samples.size)
            val firstChunk = recorder.firstChunkElapsedMs
            session.firstAudioChunkMs = firstChunk
            val dec = decoder ?: SimulatedStreamingDecoder(backend)
            val finalResult = try {
                dec.finalize(samples)
            } catch (t: Throwable) {
                sessions.fail(t.message ?: t.toString())
                ui.post {
                    binding.startStop.isEnabled = true
                    binding.startStop.text = "START"
                    setThreadControlsEnabled(true)
                    setStateLabel("ERROR")
                    setError(t.message ?: t.toString())
                }
                return@execute
            }
            val stopToFinal = System.currentTimeMillis() - stopRequestedAt
            val after = memory.snapshot()
            val peak: PeakMemory? = try {
                peakSampler?.stop()
            } catch (_: Throwable) {
                null
            }
            peakSampler = null
            sessions.complete(finalResult.text, finalResult.totalComputeMs, audioMs, stopToFinal)
            val rtf = RealTimeFactor.compute(finalResult.totalComputeMs, audioMs)
            val report = BenchmarkSession(
                sessionId = session.sessionId,
                sequence = session.sequence,
                timestampEpochMs = session.createdEpochMs,
                engine = BuildConfig.ENGINE_NAME,
                model = BuildConfig.MODEL_NAME,
                modelFiles = listOf(
                    BuildConfig.MODEL_ENCODER,
                    BuildConfig.MODEL_DECODER,
                    BuildConfig.MODEL_JOINER,
                    BuildConfig.MODEL_TOKENS,
                    BuildConfig.MODEL_BPE,
                ).joinToString(","),
                executionProvider = HardFreeze.EXECUTION_PROVIDER,
                recognitionMode = HardFreeze.RECOGNITION_MODE,
                nativeStreamingModel = HardFreeze.NATIVE_STREAMING_MODEL,
                simulatedStreaming = HardFreeze.SIMULATED_STREAMING,
                decodingMethod = HardFreeze.DECODING_METHOD,
                threadCount = session.threadCount,
                cpuCores = DeviceInfo.cpuCores(),
                androidAbi = DeviceInfo.abi(),
                androidApi = DeviceInfo.api(),
                cpuFeatures = DeviceInfo.cpuFeatures(),
                audioStartEpochMs = session.audioStartEpochMs,
                stopEpochMs = session.stopEpochMs,
                audioDurationMs = audioMs,
                timeToFirstAudioChunkMs = firstChunk,
                timeToFirstNonEmptyPartialMs = finalResult.firstNonEmptyPartialElapsedMs
                    ?: session.timeToFirstNonEmptyPartialMs,
                partialCount = finalResult.partials.size,
                lastPartialEpochMs = session.lastPartialEpochMs,
                stopToFinalMs = stopToFinal,
                totalAsrComputeMs = finalResult.totalComputeMs,
                rtf = rtf,
                recognizerInitMs = recognizerInitMs,
                finalRawTranscript = finalResult.text,
                latestPartial = session.latestPartial,
                partials = finalResult.partials,
                memoryBefore = before,
                memoryDuringSampledPeak = peak,
                memoryAfter = after,
                error = session.error,
                recordingStatus = "manual STOP; captured ${audioMs} ms",
            )
            currentReport = report
            history.add(report)
            decoder = null
            ui.post {
                binding.startStop.isEnabled = true
                binding.startStop.text = "START"
                setThreadControlsEnabled(true)
                binding.finalTranscript.text =
                    if (finalResult.text.isEmpty()) "(empty raw transcript)" else finalResult.text
                binding.latestPartial.text = session.latestPartial.ifEmpty { "(none)" }
                binding.history.text = history.asPlainText()
                binding.timing.text = buildString {
                    appendLine("TIMING:")
                    appendLine("- audio duration: ${audioMs} ms")
                    appendLine(
                        "- first partial: " +
                            (report.timeToFirstNonEmptyPartialMs?.let { "$it ms" } ?: "N/A"),
                    )
                    appendLine("- stop->final: ${stopToFinal} ms")
                    appendLine("- compute time: ${finalResult.totalComputeMs} ms")
                    appendLine("- RTF: ${RealTimeFactor.format(rtf)}")
                    appendLine("- first audio chunk: ${firstChunk} ms")
                    appendLine("- recognizer init: ${recognizerInitMs} ms")
                    appendLine("- partial count: ${finalResult.partials.size}")
                }
                binding.memory.text = buildString {
                    appendLine("MEMORY:")
                    before.let { appendLine(it.summaryLine("before")) }
                    peak?.let { appendLine(it.summaryLine()) }
                    after.let { appendLine(it.summaryLine("after")) }
                }
                setError(session.error)
                setStateLabel(if (session.error.isBlank()) "COMPLETE" else "ERROR")
            }
        }
    }

    private fun copyDiagnostic() {
        val text = DiagnosticReport.render(
            applicationId = BuildConfig.APPLICATION_ID,
            sherpaSource = BuildConfig.SHERPA_ONNX_SOURCE,
            sherpaVersion = "${BuildConfig.SHERPA_ONNX_TAG} / ${BuildConfig.SHERPA_ONNX_COMMIT}",
            sherpaLicense = BuildConfig.SHERPA_ONNX_LICENSE,
            modelName = BuildConfig.MODEL_NAME,
            modelArchitecture = BuildConfig.MODEL_ARCHITECTURE,
            modelLanguage = BuildConfig.MODEL_LANGUAGE,
            modelQuantization = BuildConfig.MODEL_QUANTIZATION,
            modelFileSizes = ModelInstaller.fileSizesBlock(),
            modelFileSha256 = ModelInstaller.fileSha256Block(),
            minSdk = PhaseInfo.MIN_SDK,
            targetSdk = PhaseInfo.TARGET_SDK,
            abi = PhaseInfo.ABI,
            deviceApi = DeviceInfo.api(),
            cpuCores = DeviceInfo.cpuCores(),
            cpuFeatures = DeviceInfo.cpuFeatures(),
            threadOptions = "1, 2, 4 (default 1)",
            current = currentReport,
            history = history.snapshot(),
            extraError = lastError,
        )
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("CARFU Phase 3B.1 diagnostic", text))
        Toast.makeText(this, "Copied diagnostic text", Toast.LENGTH_SHORT).show()
    }

    override fun onPause() {
        super.onPause()
        if (recorder.isRecording) {
            stopAndFinalize()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTickers()
        try {
            if (recorder.isRecording) recorder.stopAndFloatSamples()
        } catch (_: Throwable) {
        }
        recorder.release()
        peakSampler?.stop()
        worker.shutdownNow()
        backend.release()
        sessions.cleanup()
        decoder = null
    }

    companion object {
        private const val REQ_RECORD = 92
    }
}
