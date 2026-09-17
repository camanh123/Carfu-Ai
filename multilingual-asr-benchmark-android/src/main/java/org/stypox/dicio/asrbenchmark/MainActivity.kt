package org.stypox.dicio.asrbenchmark

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
import org.stypox.dicio.asrbenchmark.audio.Pcm16kMonoRecorder
import org.stypox.dicio.asrbenchmark.config.ContextPrompt
import org.stypox.dicio.asrbenchmark.config.LanguageMode
import org.stypox.dicio.asrbenchmark.config.ThreadOption
import org.stypox.dicio.asrbenchmark.corpus.SpokenTestTargets
import org.stypox.dicio.asrbenchmark.databinding.ActivityMainBinding
import org.stypox.dicio.asrbenchmark.metrics.MemoryProbe
import org.stypox.dicio.asrbenchmark.metrics.RealTimeFactor
import org.stypox.dicio.asrbenchmark.model.ModelInstaller
import org.stypox.dicio.asrbenchmark.nativebridge.WhisperEngine
import org.stypox.dicio.asrbenchmark.nativebridge.WhisperTranscribeRequest
import org.stypox.dicio.asrbenchmark.report.BenchmarkSession
import org.stypox.dicio.asrbenchmark.report.DiagnosticReport
import org.stypox.dicio.asrbenchmark.report.SessionHistory
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val engine = WhisperEngine()
    private val recorder = Pcm16kMonoRecorder()
    private val history = SessionHistory()
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "asr-benchmark-worker")
    }
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var memory: MemoryProbe

    @Volatile
    private var modelLoadMs: Long = -1L

    @Volatile
    private var lastNativeInfo: String = "n/a"

    @Volatile
    private var lastError: String = ""

    @Volatile
    private var current: BenchmarkSession? = null

    private var sequence = 0
    private var durationTicker: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        memory = MemoryProbe(this)

        binding.corpus.text = SpokenTestTargets.asPlainText() +
            "\nThese lines are spoken targets only. They are NOT expected transcripts."
        refreshConfigLine()
        bindControls()
        loadModelAsync()
    }

    private fun bindControls() {
        binding.languageGroup.setOnCheckedChangeListener { _, _ -> refreshConfigLine() }
        binding.contextGroup.setOnCheckedChangeListener { _, _ -> refreshConfigLine() }
        binding.threadGroup.setOnCheckedChangeListener { _, _ -> refreshConfigLine() }
        binding.startStop.setOnClickListener { onStartStop() }
        binding.copyDiagnostic.setOnClickListener { copyDiagnostic() }
    }

    private fun selectedLanguage(): LanguageMode =
        if (binding.langVi.isChecked) LanguageMode.VI else LanguageMode.AUTO

    private fun contextOn(): Boolean = binding.contextOn.isChecked

    private fun selectedThreads(): ThreadOption =
        if (binding.threads2.isChecked) ThreadOption.TWO else ThreadOption.FOUR

    private fun refreshConfigLine() {
        val sizeMb = BuildConfig.MODEL_BYTES / (1024.0 * 1024.0)
        binding.configLine.text = buildString {
            append("ENGINE=${BuildConfig.ENGINE_NAME}  ")
            append("MODEL=${BuildConfig.MODEL_NAME}  ")
            append("MODEL SIZE=${"%.1f".format(sizeMb)}MB (${BuildConfig.MODEL_BYTES} B)  ")
            append("LANG=${selectedLanguage().displayName}  ")
            append("CONTEXT=${ContextPrompt.display(contextOn())}  ")
            append("THREADS=${selectedThreads().nThreads}  ")
            append("ABI=arm64-v8a  ")
            append("minSdk=29")
        }
    }

    private fun setStatus(text: String) {
        binding.status.text = text
    }

    private fun setError(text: String) {
        lastError = text
        binding.error.text = if (text.isBlank()) "" else "ERROR: $text"
    }

    private fun loadModelAsync() {
        setStatus("LOADING MODEL…")
        setError("")
        worker.execute {
            val t0 = System.nanoTime()
            try {
                val file = ModelInstaller.ensureInstalled(this)
                engine.load(file.absolutePath)
                modelLoadMs = (System.nanoTime() - t0) / 1_000_000L
                lastNativeInfo = engine.systemInfo()
                ui.post {
                    setStatus("IDLE — model loaded in ${modelLoadMs}ms. Press START.")
                    refreshConfigLine()
                    binding.resultMetrics.text = "MODEL LOAD TIME: ${modelLoadMs} ms\n" +
                        "CPU/THREADS: ${selectedThreads().nThreads} / cores=${Runtime.getRuntime().availableProcessors()}\n" +
                        "WHISPER_SYSTEM_INFO: $lastNativeInfo"
                }
            } catch (t: Throwable) {
                modelLoadMs = (System.nanoTime() - t0) / 1_000_000L
                val msg = t.message ?: t.toString()
                ui.post {
                    setStatus("MODEL LOAD FAILED")
                    setError(msg)
                    binding.resultMetrics.text = "MODEL LOAD TIME: ${modelLoadMs} ms (failed)\n" +
                        "Failure is a valid Phase 3A result. Do not hide it."
                }
            }
        }
    }

    private fun onStartStop() {
        if (recorder.isRecording) {
            stopAndTranscribe()
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
        }
    }

    private fun startCapture() {
        if (!engine.isLoaded) {
            setError("model is not loaded; cannot capture for transcription")
            return
        }
        try {
            recorder.start()
        } catch (t: Throwable) {
            setError(t.message ?: "AudioRecord start failed")
            setStatus("CAPTURE FAILED")
            return
        }
        binding.startStop.text = "STOP"
        setError("")
        binding.rawTranscript.text = "(recording…)"
        setStatus("RECORDING")
        startDurationTicker()
    }

    private fun startDurationTicker() {
        stopDurationTicker()
        val r = object : Runnable {
            override fun run() {
                if (!recorder.isRecording) return
                val ms = recorder.recordedDurationMs()
                setStatus("RECORDING  AUDIO DURATION=${ms} ms")
                ui.postDelayed(this, 200)
            }
        }
        durationTicker = r
        ui.post(r)
    }

    private fun stopDurationTicker() {
        durationTicker?.let { ui.removeCallbacks(it) }
        durationTicker = null
    }

    private fun stopAndTranscribe() {
        stopDurationTicker()
        binding.startStop.isEnabled = false
        setStatus("STOPPING CAPTURE…")
        worker.execute {
            val samples = try {
                recorder.stopAndFloatSamples()
            } catch (t: Throwable) {
                ui.post {
                    binding.startStop.isEnabled = true
                    binding.startStop.text = "START"
                    setStatus("CAPTURE STOP FAILED")
                    setError(t.message ?: t.toString())
                }
                return@execute
            }
            val audioMs = Pcm16kMonoRecorder.durationMs(samples.size)
            ui.post {
                binding.startStop.text = "START"
                setStatus("TRANSCRIBING ${audioMs} ms audio…")
            }
            runTranscription(samples, audioMs)
            ui.post { binding.startStop.isEnabled = true }
        }
    }

    private fun runTranscription(samples: FloatArray, audioMs: Long) {
        val lang = uiSubmit { selectedLanguage() }
        val ctxOn = uiSubmit { contextOn() }
        val threads = uiSubmit { selectedThreads() }
        val before = memory.snapshot()
        val peak = memory.startPeakSampler()
        val t0 = System.nanoTime()
        var error = ""
        var raw = ""
        var detected = ""
        var conf = -1f
        var segments = 0
        val transcribeMs = try {
            if (!engine.isLoaded) {
                throw IllegalStateException("model not loaded")
            }
            val result = engine.transcribe(
                WhisperTranscribeRequest(
                    samples = samples,
                    languageMode = lang,
                    contextEnabled = ctxOn,
                    nThreads = threads.nThreads,
                ),
            )
            raw = result.rawTranscript
            detected = result.detectedLanguage
            segments = result.segmentCount
            (System.nanoTime() - t0) / 1_000_000L
        } catch (t: Throwable) {
            error = t.message ?: t.toString()
            (System.nanoTime() - t0) / 1_000_000L
        }
        if (error.isBlank()) {
            conf = engine.probeLanguageConfidence(threads.nThreads)
        }
        val after = memory.snapshot()
        val peakMem = peak.stop()
        val rtf = RealTimeFactor.compute(transcribeMs, audioMs)
        sequence += 1
        val session = BenchmarkSession(
            sequence = sequence,
            timestampEpochMs = System.currentTimeMillis(),
            engine = BuildConfig.ENGINE_NAME,
            model = BuildConfig.MODEL_NAME,
            modelSizeBytes = BuildConfig.MODEL_BYTES,
            modelSha256 = BuildConfig.MODEL_SHA256,
            languageMode = lang,
            contextOn = ctxOn,
            contextPrompt = ContextPrompt.VOCABULARY_HINT,
            nThreads = threads.nThreads,
            cpuCores = Runtime.getRuntime().availableProcessors(),
            nativeSystemInfo = lastNativeInfo,
            audioDurationMs = audioMs,
            modelLoadTimeMs = modelLoadMs,
            transcriptionTimeMs = transcribeMs,
            rtf = rtf,
            rawTranscript = raw,
            detectedLanguage = detected,
            languageConfidence = conf,
            segmentCount = segments,
            memoryBefore = before,
            memoryAfter = after,
            peakMemory = peakMem,
            error = error,
            recordingStatus = "captured ${audioMs} ms then stopped",
        )
        current = session
        history.add(session)
        ui.post {
            binding.rawTranscript.text = if (raw.isEmpty()) "(empty raw transcript)" else raw
            binding.history.text = history.asPlainText()
            binding.resultMetrics.text = DiagnosticReport.sessionBlock(session)
            setError(error)
            setStatus(
                if (error.isBlank()) {
                    "DONE  duration=${audioMs}ms  infer=${transcribeMs}ms  RTF=${RealTimeFactor.format(rtf)}"
                } else {
                    "FAILED  duration=${audioMs}ms  infer=${transcribeMs}ms"
                },
            )
        }
    }

    private fun <T> uiSubmit(block: () -> T): T {
        var result: T? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        ui.post {
            result = block()
            latch.countDown()
        }
        latch.await()
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun copyDiagnostic() {
        val text = DiagnosticReport.render(
            phaseBase = PhaseInfo.PHASE_BASE,
            applicationId = BuildConfig.APPLICATION_ID,
            whisperSource = BuildConfig.WHISPER_CPP_REPO,
            whisperCommit = "${BuildConfig.WHISPER_CPP_TAG} / ${BuildConfig.WHISPER_CPP_COMMIT}",
            whisperLicense = BuildConfig.WHISPER_CPP_LICENSE,
            modelName = BuildConfig.MODEL_NAME,
            modelMultilingual = BuildConfig.MODEL_MULTILINGUAL,
            modelSizeBytes = BuildConfig.MODEL_BYTES,
            modelQuantization = BuildConfig.MODEL_QUANTIZATION,
            modelSha256 = BuildConfig.MODEL_SHA256,
            modelSource = BuildConfig.MODEL_SOURCE_URL,
            minSdk = 29,
            abi = "arm64-v8a",
            nativeOptimization = "CMake Release -O3 -DNDEBUG; no host-native; no armv8.2+fp16 extra ISA",
            current = current,
            history = history.snapshot(),
            extraError = lastError,
            cpuCores = Runtime.getRuntime().availableProcessors(),
            nativeSystemInfo = lastNativeInfo,
        )
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("CARFU Phase 3A diagnostic", text))
        Toast.makeText(this, "Copied diagnostic text", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDurationTicker()
        try {
            if (recorder.isRecording) recorder.stopAndFloatSamples()
        } catch (_: Throwable) {
        }
        worker.shutdownNow()
        engine.release()
    }

    companion object {
        const val PHASE_BASE: String = PhaseInfo.PHASE_BASE
        private const val REQ_RECORD = 91
    }
}
