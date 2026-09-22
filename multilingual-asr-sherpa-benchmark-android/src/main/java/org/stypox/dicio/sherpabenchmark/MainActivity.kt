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
import org.stypox.dicio.sherpabenchmark.audio.ReferenceAudioStore
import org.stypox.dicio.sherpabenchmark.config.ThreadOption
import org.stypox.dicio.sherpabenchmark.corpus.SpokenTestTargets
import org.stypox.dicio.sherpabenchmark.databinding.ActivityMainBinding
import org.stypox.dicio.sherpabenchmark.diagnostics.BenchmarkJournal
import org.stypox.dicio.sherpabenchmark.diagnostics.DeviceInfo
import org.stypox.dicio.sherpabenchmark.diagnostics.SessionJournal
import org.stypox.dicio.sherpabenchmark.diagnostics.ThermalProbe
import org.stypox.dicio.sherpabenchmark.engine.BenchState
import org.stypox.dicio.sherpabenchmark.engine.BoundedPartialLimits
import org.stypox.dicio.sherpabenchmark.engine.BoundedPartialPolicy
import org.stypox.dicio.sherpabenchmark.engine.BoundedScheduleDecision
import org.stypox.dicio.sherpabenchmark.engine.DecodeGate
import org.stypox.dicio.sherpabenchmark.engine.FinalizeGuard
import org.stypox.dicio.sherpabenchmark.engine.InteractiveDecodeMode
import org.stypox.dicio.sherpabenchmark.engine.InteractiveOptimizeLimits
import org.stypox.dicio.sherpabenchmark.engine.OptimizedPartialPolicy
import org.stypox.dicio.sherpabenchmark.engine.PartialScheduleDecision
import org.stypox.dicio.sherpabenchmark.engine.RecognizerReuse
import org.stypox.dicio.sherpabenchmark.engine.MemorySoakRunner
import org.stypox.dicio.sherpabenchmark.engine.RecognitionConfigFactory
import org.stypox.dicio.sherpabenchmark.engine.RecordingPolicy
import org.stypox.dicio.sherpabenchmark.engine.SessionIsolationException
import org.stypox.dicio.sherpabenchmark.engine.SessionMachine
import org.stypox.dicio.sherpabenchmark.engine.SherpaOfflineBackend
import org.stypox.dicio.sherpabenchmark.engine.SimulatedStreamingDecoder
import org.stypox.dicio.sherpabenchmark.engine.SoakResult
import org.stypox.dicio.sherpabenchmark.engine.ThreadBenchmarkResult
import org.stypox.dicio.sherpabenchmark.engine.ThreadBenchmarkRunner
import org.stypox.dicio.sherpabenchmark.freeze.BenchmarkLimits
import org.stypox.dicio.sherpabenchmark.freeze.HardFreeze
import org.stypox.dicio.sherpabenchmark.metrics.MemoryProbe
import org.stypox.dicio.sherpabenchmark.metrics.MemorySnapshot
import org.stypox.dicio.sherpabenchmark.metrics.PeakMemory
import org.stypox.dicio.sherpabenchmark.metrics.RealTimeFactor
import org.stypox.dicio.sherpabenchmark.model.ModelInstaller
import org.stypox.dicio.sherpabenchmark.report.BenchmarkSession
import org.stypox.dicio.sherpabenchmark.report.ControlledBenchmarkReport
import org.stypox.dicio.sherpabenchmark.report.DiagnosticReport
import org.stypox.dicio.sherpabenchmark.report.SessionHistory
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val backend = SherpaOfflineBackend()
    private val recorder = Pcm16kMonoRecorder()
    private val history = SessionHistory()
    private val sessions = SessionMachine()
    private val decodeGate = DecodeGate()
    private val finalizeGuard = FinalizeGuard()
    private val optimizedPolicy = OptimizedPartialPolicy()
    private val boundedPolicy = BoundedPartialPolicy()
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "sherpa-benchmark-worker")
    }
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var memory: MemoryProbe
    private lateinit var journal: SessionJournal
    private lateinit var benchJournal: BenchmarkJournal
    private lateinit var refStore: ReferenceAudioStore

    @Volatile
    private var recognizerInitMs: Long = -1L

    @Volatile
    private var lastError: String = ""

    @Volatile
    private var currentReport: BenchmarkSession? = null

    @Volatile
    private var alive: Boolean = true

    private var decoder: SimulatedStreamingDecoder? = null
    private var durationTicker: Runnable? = null
    private var peakSampler: MemoryProbe.PeakSampler? = null
    private var memoryStart: MemorySnapshot? = null
    private var referenceTicker: Runnable? = null

    @Volatile
    private var recordingReference: Boolean = false

    @Volatile
    private var benchRunning: Boolean = false

    private val benchCancel = AtomicBoolean(false)
    private var lastThreadResult: ThreadBenchmarkResult? = null
    private var lastSoakResult: SoakResult? = null

    @Volatile
    private var sessionMode: InteractiveDecodeMode = InteractiveDecodeMode.DEFAULT

    private val legacyRequested = java.util.concurrent.atomic.AtomicInteger(0)
    private val legacyExecuted = java.util.concurrent.atomic.AtomicInteger(0)
    private val legacyCoalesced = java.util.concurrent.atomic.AtomicInteger(0)
    private val legacySkippedBusy = java.util.concurrent.atomic.AtomicInteger(0)
    private val sessionFinalDecodeCount = java.util.concurrent.atomic.AtomicInteger(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        memory = MemoryProbe(this)
        journal = (application as SherpaBenchmarkApp).journal
        benchJournal = (application as SherpaBenchmarkApp).benchJournal
        refStore = ReferenceAudioStore(File(filesDir, "reference-audio"))
        refStore.load()
        journal.markLifecycle("MainActivity.onCreate")

        binding.corpus.text = SpokenTestTargets.asPlainText() +
            "\nMAX RECORDING: ${BenchmarkLimits.MAX_RECORDING_MS} ms auto-stop " +
            "(${BenchmarkLimits.AUTO_STOP_REASON}). Not production Voice."
        showPreviousJournal()
        showPreviousBenchmark()
        showReference()
        refreshConfigLine()
        bindControls()
        setStateLabel("IDLE")
        loadRecognizerAsync(selectedThreads().nThreads)
    }

    private fun showPreviousJournal() {
        val abnormal = journal.previousEndedAbnormally
        binding.previousSession.text =
            "PREVIOUS SESSION ENDED ABNORMALLY: ${HardFreeze.yesNo(abnormal)}"
        binding.lastJournal.text = "LAST SESSION JOURNAL\n" + journal.renderLast()
    }

    private fun bindControls() {
        binding.threadGroup.setOnCheckedChangeListener { _, _ ->
            if (sessions.canStart()) {
                refreshConfigLine()
            }
        }
        binding.interactiveModeGroup.setOnCheckedChangeListener { _, _ ->
            if (sessions.canStart()) {
                refreshConfigLine()
            }
        }
        binding.startStop.setOnClickListener { onStartStop() }
        binding.copyDiagnostic.setOnClickListener { copyDiagnostic() }
        binding.recordReference.setOnClickListener { onRecordReference() }
        binding.deleteReference.setOnClickListener { onDeleteReference() }
        binding.runThreadBenchmark.setOnClickListener { onRunThreadBenchmark() }
        binding.runMemorySoak.setOnClickListener { onRunMemorySoak() }
        binding.stopBenchmark.setOnClickListener { benchCancel.set(true) }
        binding.copyBenchmark.setOnClickListener { copyBenchmarkReport() }
    }

    private fun selectedInteractiveMode(): InteractiveDecodeMode = when {
        binding.modeLegacy.isChecked -> InteractiveDecodeMode.LEGACY
        binding.modeOptimized.isChecked -> InteractiveDecodeMode.OPTIMIZED
        else -> InteractiveDecodeMode.BOUNDED
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
            append("INTERACTIVE_MODE=${selectedInteractiveMode().reportName}\n")
            append("MODE=${HardFreeze.RECOGNITION_MODE}\n")
            append("FIXED_AUDIO=${HardFreeze.FIXED_AUDIO_BENCHMARK_MODE}\n")
            append("NATIVE_STREAMING=${HardFreeze.yesNo(HardFreeze.NATIVE_STREAMING_MODEL)}  ")
            append("SIMULATED_STREAMING=${HardFreeze.yesNo(HardFreeze.SIMULATED_STREAMING)}\n")
            append("THREADS=${selectedThreads().nThreads}  ABI=${PhaseInfo.ABI}  ")
            append("minSdk=${PhaseInfo.MIN_SDK} targetSdk=${PhaseInfo.TARGET_SDK}  ")
            append("API=${DeviceInfo.api()}  cores=${DeviceInfo.cpuCores()}\n")
            append("MAX_RECORDING=${BenchmarkLimits.MAX_RECORDING_MS} ms  ")
            append("AUTO_STOP=${BenchmarkLimits.AUTO_STOP_REASON}")
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
        binding.modeLegacy.isEnabled = enabled
        binding.modeOptimized.isEnabled = enabled
        binding.modeBounded.isEnabled = enabled
    }

    private fun loadRecognizerAsync(threads: Int) {
        setStateLabel("IDLE")
        setError("")
        worker.execute {
            try {
                val paths = ModelInstaller.ensureInstalled(this)
                val cfg = RecognitionConfigFactory.create(threads, paths)
                recognizerInitMs = backend.init(cfg)
                journal.update {
                    it.copy(
                        recognizerState = "ready",
                        lastNativeOp = backend.lastNativeOp,
                    )
                }
                postUi {
                    setStateLabel("IDLE")
                    binding.timing.text = "MODEL / RECOGNIZER INIT TIME: ${recognizerInitMs} ms\n" +
                        "MAX RECORDING ${BenchmarkLimits.MAX_RECORDING_MS} ms. " +
                        "CPU only. greedy_search. simulated streaming."
                    refreshConfigLine()
                }
            } catch (t: Throwable) {
                val msg = t.message ?: t.toString()
                postUi {
                    setStateLabel("ERROR")
                    setError(msg)
                }
            }
        }
    }

    private fun onStartStop() {
        if (recordingReference) {
            stopReferenceCapture()
            return
        }
        if (benchRunning) {
            setError("controlled benchmark running; use STOP BENCHMARK")
            return
        }
        if (recorder.isRecording || sessions.state == BenchState.RECORDING) {
            stopAndFinalize(BenchmarkLimits.MANUAL_STOP_REASON)
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
        } else if (requestCode == REQ_RECORD_REF && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startReferenceCapture()
        } else if (requestCode == REQ_RECORD || requestCode == REQ_RECORD_REF) {
            setError("RECORD_AUDIO permission denied")
            setStateLabel("ERROR")
        }
    }

    private fun startCapture() {
        if (benchRunning || recordingReference) {
            setError("controlled benchmark or reference capture in progress")
            setStateLabel("ERROR")
            return
        }
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
        finalizeGuard.reset()
        decodeGate.reset()
        optimizedPolicy.reset()
        boundedPolicy.reset()
        legacyRequested.set(0)
        legacyExecuted.set(0)
        legacyCoalesced.set(0)
        legacySkippedBusy.set(0)
        sessionFinalDecodeCount.set(0)
        sessionMode = selectedInteractiveMode()
        val session = sessions.current!!
        journal.beginSession(session.sessionId, session.createdEpochMs)
        worker.execute {
            try {
                if (RecognizerReuse.needsRecreate(backend.isReady, backend.loadedThreads, threads)) {
                    val paths = ModelInstaller.ensureInstalled(this)
                    val cfg = RecognitionConfigFactory.create(threads, paths)
                    recognizerInitMs = backend.init(cfg)
                }
                decoder = when (sessionMode) {
                    InteractiveDecodeMode.LEGACY -> SimulatedStreamingDecoder(backend)
                    InteractiveDecodeMode.OPTIMIZED,
                    InteractiveDecodeMode.BOUNDED,
                    -> SimulatedStreamingDecoder(backend, minNewSamples = 1)
                }
                memoryStart = memory.snapshot()
                recorder.start()
                peakSampler = memory.startPeakSampler()
                postUi {
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
                postUi {
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
        val delayMs = when (sessionMode) {
            InteractiveDecodeMode.BOUNDED -> BoundedPartialLimits.POLL_MS
            InteractiveDecodeMode.OPTIMIZED -> InteractiveOptimizeLimits.OPTIMIZED_POLL_MS
            InteractiveDecodeMode.LEGACY -> HardFreeze.SIMULATED_STREAMING_CHUNK_MS
        }
        val r = object : Runnable {
            override fun run() {
                if (!alive || !recorder.isRecording || finalizeGuard.hasStarted) return
                val ms = recorder.recordedDurationMs()
                setStateLabel(
                    "RECORDING  MODE=${sessionMode.reportName}  AUDIO DURATION=${ms} ms / max ${BenchmarkLimits.MAX_RECORDING_MS}",
                )
                if (RecordingPolicy.shouldAutoStop(ms)) {
                    stopAndFinalize(RecordingPolicy.autoStopReason())
                    return
                }
                when (sessionMode) {
                    InteractiveDecodeMode.LEGACY -> scheduleLegacyPartial()
                    InteractiveDecodeMode.OPTIMIZED -> scheduleOptimizedPartial()
                    InteractiveDecodeMode.BOUNDED -> scheduleBoundedPartial()
                }
                ui.postDelayed(this, delayMs)
            }
        }
        durationTicker = r
        ui.post(r)
    }

    private fun stopTickers() {
        durationTicker?.let { ui.removeCallbacks(it) }
        durationTicker = null
    }

    private fun scheduleLegacyPartial() {
        legacyRequested.incrementAndGet()
        if (decodeGate.inFlightCount == 1 && decodeGate.pendingCount == 1) {
            legacySkippedBusy.incrementAndGet()
        }
        val scheduled = decodeGate.trySchedule({ worker.execute(it) }) {
            maybePartial()
        }
        if (!scheduled) legacyCoalesced.incrementAndGet()
    }

    private fun scheduleOptimizedPartial() {
        val decision = optimizedPolicy.decide(
            audioMs = recorder.recordedDurationMs(),
            currentSamples = recorder.recordedSampleCount(),
            inFlight = decodeGate.inFlightCount == 1,
            pending = decodeGate.pendingCount == 1,
        )
        when (decision) {
            PartialScheduleDecision.REQUEST, PartialScheduleDecision.COALESCE -> {
                decodeGate.trySchedule({ worker.execute(it) }) {
                    maybePartial()
                }
            }
            PartialScheduleDecision.SKIP_TOO_EARLY,
            PartialScheduleDecision.SKIP_NO_NEW_AUDIO,
            PartialScheduleDecision.SKIP_BUSY,
            -> Unit
        }
    }

    private fun scheduleBoundedPartial() {
        val decision = boundedPolicy.decide(
            audioMs = recorder.recordedDurationMs(),
            currentSamples = recorder.recordedSampleCount(),
            inFlight = decodeGate.inFlightCount == 1,
            recording = recorder.isRecording && !finalizeGuard.hasStarted,
        )
        if (decision == BoundedScheduleDecision.REQUEST) {
            decodeGate.trySchedule({ worker.execute(it) }) {
                maybePartial()
            }
        }
    }

    private fun maybePartial() {
        if (finalizeGuard.hasStarted) return
        if (sessionMode == InteractiveDecodeMode.OPTIMIZED && optimizedPolicy.stopRequested) return
        if (sessionMode == InteractiveDecodeMode.BOUNDED &&
            (boundedPolicy.stopRequested || !boundedPolicy.canExecutePartial())
        ) {
            if (boundedPolicy.stopRequested) boundedPolicy.droppedStop.incrementAndGet()
            return
        }
        val session = sessions.current ?: return
        val dec = decoder ?: return
        if (!recorder.isRecording) return
        val samples = recorder.snapshotFloatSamples()
        val elapsed = recorder.recordedDurationMs()
        val before = dec.decodeAttempts
        val event = dec.tryPartial(samples, elapsed)
        if (dec.decodeAttempts > before) {
            when (sessionMode) {
                InteractiveDecodeMode.BOUNDED ->
                    boundedPolicy.onPartialExecuted(samples.size, dec.lastDecodeDurationMs)
                InteractiveDecodeMode.OPTIMIZED ->
                    optimizedPolicy.onPartialExecuted(samples.size, dec.lastDecodeDurationMs)
                InteractiveDecodeMode.LEGACY ->
                    legacyExecuted.incrementAndGet()
            }
        }
        if (event != null) {
            session.replaceLatestPartial(event)
            postUi { binding.latestPartial.text = event.text }
        }
        persistJournal("partial-decode")
        if (RecordingPolicy.shouldAutoStop(elapsed) && !finalizeGuard.hasStarted) {
            postUi { stopAndFinalize(RecordingPolicy.autoStopReason()) }
        }
    }

    private fun persistJournal(nativeHint: String) {
        val dec = decoder
        val mem = peakSampler?.latest ?: memory.snapshot()
        journal.update {
            it.copy(
                inProgress = true,
                audioDurationMs = recorder.recordedDurationMs(),
                chunkCount = recorder.chunkCount,
                decodeCount = dec?.decodeAttempts ?: 0,
                partialCount = dec?.partialCount ?: 0,
                lastSuccessfulPartial = sessions.current?.latestPartial ?: it.lastSuccessfulPartial,
                lastPartialEpochMs = sessions.current?.lastPartialEpochMs ?: 0L,
                lastDecodeMs = dec?.lastDecodeDurationMs ?: 0L,
                maxDecodeMs = dec?.maximumDecodeMs ?: 0L,
                pendingDecodeCount = decodeGate.pendingCount,
                javaUsedBytes = mem.javaUsedBytes,
                nativeHeapBytes = mem.nativeHeapAllocatedBytes,
                pssKb = mem.pssKb,
                availMemBytes = mem.availMemBytes,
                lowMemory = mem.lowMemory,
                javaThreadCount = Thread.activeCount(),
                recognizerState = if (backend.isReady) "ready" else "none",
                lastNativeOp = backend.lastNativeOp.ifBlank { nativeHint },
            )
        }
    }

    private fun stopAndFinalize(reason: String) {
        if (!finalizeGuard.tryBegin()) return
        stopTickers()
        recorder.requestStop()
        if (sessionMode == InteractiveDecodeMode.OPTIMIZED) {
            optimizedPolicy.markStop()
            decodeGate.discardPending()
        }
        if (sessionMode == InteractiveDecodeMode.BOUNDED) {
            boundedPolicy.markStop()
            decodeGate.discardPending()
        }
        val stopRequestedAt = System.currentTimeMillis()
        journal.update { it.copy(autoStopReason = reason, lastLifecycleEvent = "finalize:$reason") }
        if (alive) {
            try {
                binding.startStop.isEnabled = false
                setStateLabel("FINALIZING")
            } catch (_: Throwable) {
            }
        }
        worker.execute {
            runFinalize(reason, stopRequestedAt)
        }
    }

    private fun runFinalize(reason: String, stopRequestedAt: Long) {
        val session = try {
            if (sessions.canStop()) sessions.requestStop() else sessions.current
        } catch (e: SessionIsolationException) {
            postUi {
                binding.startStop.isEnabled = true
                setError(e.message ?: "double STOP")
            }
            finalizeGuard.markFinished()
            return
        } ?: run {
            finalizeGuard.markFinished()
            return
        }
        session.autoStopReason = reason
        val before = memoryStart ?: memory.snapshot()
        val samples = try {
            recorder.awaitStopped()
            recorder.snapshotFloatSamples()
        } catch (t: Throwable) {
            sessions.fail(t.message ?: t.toString())
            finishUiError(t.message ?: t.toString())
            return
        }
        val audioMs = Pcm16kMonoRecorder.durationMs(samples.size)
        val firstChunk = recorder.firstChunkElapsedMs
        session.firstAudioChunkMs = firstChunk
        val dec = decoder ?: SimulatedStreamingDecoder(backend)
        val finalResult = try {
            dec.finalize(samples)
        } catch (t: Throwable) {
            sessions.fail(t.message ?: t.toString())
            finishUiError(t.message ?: t.toString())
            return
        }
        sessionFinalDecodeCount.incrementAndGet()
        if (sessionMode == InteractiveDecodeMode.OPTIMIZED) {
            optimizedPolicy.onFinalExecuted()
        }
        if (sessionMode == InteractiveDecodeMode.BOUNDED) {
            boundedPolicy.onFinalExecuted()
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
            partialCount = dec.partialCount,
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
            recordingStatus = "$reason; captured ${audioMs} ms",
            autoStopReason = reason,
            chunkCount = recorder.chunkCount,
            decodeCount = dec.decodeAttempts,
            maxDecodeMs = dec.maximumDecodeMs,
            pendingDecodeCount = decodeGate.pendingCount,
            interactiveMode = sessionMode.reportName,
            decodeRequested = when (sessionMode) {
                InteractiveDecodeMode.BOUNDED -> boundedPolicy.requested.get()
                InteractiveDecodeMode.OPTIMIZED -> optimizedPolicy.requested.get()
                InteractiveDecodeMode.LEGACY -> legacyRequested.get()
            },
            decodeExecuted = when (sessionMode) {
                InteractiveDecodeMode.BOUNDED -> boundedPolicy.executed.get()
                InteractiveDecodeMode.OPTIMIZED -> optimizedPolicy.executed.get()
                InteractiveDecodeMode.LEGACY -> legacyExecuted.get()
            },
            decodeCoalesced = when (sessionMode) {
                InteractiveDecodeMode.BOUNDED -> 0
                InteractiveDecodeMode.OPTIMIZED -> optimizedPolicy.coalesced.get()
                InteractiveDecodeMode.LEGACY -> legacyCoalesced.get()
            },
            decodeSkippedBusy = when (sessionMode) {
                InteractiveDecodeMode.BOUNDED -> boundedPolicy.skippedBusy.get()
                InteractiveDecodeMode.OPTIMIZED -> optimizedPolicy.skippedBusy.get()
                InteractiveDecodeMode.LEGACY -> legacySkippedBusy.get()
            },
            decodeDroppedBudget = if (sessionMode == InteractiveDecodeMode.BOUNDED) {
                boundedPolicy.droppedBudget.get()
            } else {
                0
            },
            decodeDroppedStop = if (sessionMode == InteractiveDecodeMode.BOUNDED) {
                boundedPolicy.droppedStop.get()
            } else {
                0
            },
            partialDecodeCount = when (sessionMode) {
                InteractiveDecodeMode.BOUNDED -> boundedPolicy.executed.get()
                InteractiveDecodeMode.OPTIMIZED -> optimizedPolicy.executed.get()
                InteractiveDecodeMode.LEGACY -> legacyExecuted.get()
            },
            finalDecodeCount = sessionFinalDecodeCount.get(),
            finalSampleCount = samples.size,
            boundedPartialLogs = if (sessionMode == InteractiveDecodeMode.BOUNDED) {
                boundedPolicy.partialLogs()
            } else {
                emptyList()
            },
        )
        currentReport = report
        history.add(report)
        journal.update {
            it.copy(
                inProgress = false,
                audioDurationMs = audioMs,
                chunkCount = report.chunkCount,
                decodeCount = report.decodeCount,
                partialCount = report.partialCount,
                lastSuccessfulPartial = session.latestPartial,
                autoStopReason = reason,
                lastNativeOp = backend.lastNativeOp,
                javaUsedBytes = after.javaUsedBytes,
                nativeHeapBytes = after.nativeHeapAllocatedBytes,
                pssKb = after.pssKb,
                availMemBytes = after.availMemBytes,
                lowMemory = after.lowMemory,
                recognizerState = if (backend.isReady) "ready" else "none",
            )
        }
        journal.completeNormally()
        decoder = null
        if (!finalizeGuard.tryReleaseOnce()) {
            // per-session resources already released
        }
        finalizeGuard.markFinished()
        postUi {
            binding.startStop.isEnabled = true
            binding.startStop.text = "START"
            setThreadControlsEnabled(true)
            binding.finalTranscript.text =
                if (finalResult.text.isEmpty()) "(empty raw transcript)" else finalResult.text
            binding.latestPartial.text = session.latestPartial.ifEmpty { "(none)" }
            binding.history.text = history.asPlainText()
            binding.timing.text = buildString {
                appendLine("TIMING:")
                appendLine("- MODE: ${sessionMode.reportName}")
                appendLine("- THREADS: ${session.threadCount}")
                appendLine("- audio duration: ${audioMs} ms")
                appendLine("- max recording: ${BenchmarkLimits.MAX_RECORDING_MS} ms")
                appendLine("- AUTO_STOP_REASON: $reason")
                appendLine(
                    "- first partial: " +
                        (report.timeToFirstNonEmptyPartialMs?.let { "$it ms" } ?: "N/A"),
                )
                appendLine("- stop->final: ${stopToFinal} ms")
                appendLine("- compute time: ${finalResult.totalComputeMs} ms")
                appendLine("- RTF: ${RealTimeFactor.format(rtf)}")
                appendLine("- first audio chunk: ${firstChunk} ms")
                appendLine("- recognizer init: ${recognizerInitMs} ms")
                appendLine("- partial count: ${dec.partialCount}")
                appendLine("- partial decode count: ${report.partialDecodeCount}")
                appendLine("- decode count: ${dec.decodeAttempts}")
                appendLine("- max decode: ${dec.maximumDecodeMs} ms")
                appendLine("- FINAL samples: ${samples.size}")
                appendLine("- DECODE_REQUESTED: ${report.decodeRequested}")
                appendLine("- DECODE_EXECUTED: ${report.decodeExecuted}")
                appendLine("- DECODE_COALESCED: ${report.decodeCoalesced}")
                appendLine("- DECODE_SKIPPED_BUSY: ${report.decodeSkippedBusy}")
                appendLine("- DECODE_DROPPED_BUDGET: ${report.decodeDroppedBudget}")
                appendLine("- DECODE_DROPPED_STOP: ${report.decodeDroppedStop}")
                appendLine("- FINAL_DECODE_COUNT: ${report.finalDecodeCount}")
                report.boundedPartialLogs.forEach { p ->
                    appendLine(
                        "- PARTIAL_INDEX=${p.index} AUDIO_SNAPSHOT_MS=${p.audioSnapshotMs} " +
                            "NEW_AUDIO_SINCE_PREVIOUS_PARTIAL_MS=${p.newAudioSincePreviousMs} " +
                            "DECODE_DURATION_MS=${p.decodeDurationMs}",
                    )
                }
            }
            binding.memory.text = buildString {
                appendLine("MEMORY (start / sampled peak / latest):")
                before.let { appendLine(it.summaryLine("start")) }
                peak?.let { appendLine(it.summaryLine()) }
                after.let { appendLine(it.summaryLine("latest")) }
            }
            showPreviousJournal()
            setError(session.error)
            setStateLabel(if (session.error.isBlank()) "COMPLETE" else "ERROR")
        }
    }

    private fun finishUiError(msg: String) {
        journal.update { it.copy(inProgress = true, recognizerState = "error") }
        decoder = null
        finalizeGuard.tryReleaseOnce()
        finalizeGuard.markFinished()
        postUi {
            binding.startStop.isEnabled = true
            binding.startStop.text = "START"
            setThreadControlsEnabled(true)
            setStateLabel("ERROR")
            setError(msg)
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
            threadOptions = "1, 2, 4 (default 4)",
            current = currentReport,
            history = history.snapshot(),
            extraError = lastError,
            previousEndedAbnormally = journal.previousEndedAbnormally,
            lastJournal = journal.renderLast(),
            maxRecordingMs = BenchmarkLimits.MAX_RECORDING_MS,
        )
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("CARFU Phase 3B.1.4 diagnostic", text))
        Toast.makeText(this, "Copied diagnostic text", Toast.LENGTH_SHORT).show()
    }

    private fun showPreviousBenchmark() {
        binding.previousBenchmark.text = benchJournal.renderPreviousBanner()
        if (benchJournal.lastReport.isNotBlank()) {
            binding.benchReport.text = benchJournal.lastReport
        }
    }

    private fun showReference() {
        val audio = refStore.current
        binding.referenceAudio.text = audio?.summaryLine() ?: "REFERENCE AUDIO:\n(none)"
    }

    private fun selectedSoakThreads(): Int = when {
        binding.soakThreads4.isChecked -> 4
        binding.soakThreads2.isChecked -> 2
        else -> 1
    }

    private fun selectedSoakIterations(): Int = when {
        binding.soakIter10.isChecked -> 10
        binding.soakIter50.isChecked -> 50
        else -> 30
    }

    private fun onRecordReference() {
        if (benchRunning) {
            setError("controlled benchmark running")
            return
        }
        if (recordingReference || recorder.isRecording) {
            stopReferenceCapture()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQ_RECORD_REF,
            )
            return
        }
        startReferenceCapture()
    }

    private fun startReferenceCapture() {
        if (benchRunning || sessions.state == BenchState.RECORDING) {
            setError("busy")
            return
        }
        try {
            recordingReference = true
            recorder.start()
            binding.recordReference.text = "STOP"
            binding.startStop.isEnabled = false
            setStateLabel("RECORDING REFERENCE")
            startReferenceTicker()
        } catch (t: Throwable) {
            recordingReference = false
            setError(t.message ?: t.toString())
        }
    }

    private fun startReferenceTicker() {
        stopReferenceTicker()
        val r = object : Runnable {
            override fun run() {
                if (!alive || !recordingReference || !recorder.isRecording) return
                val ms = recorder.recordedDurationMs()
                setStateLabel("RECORDING REFERENCE  AUDIO DURATION=$ms ms")
                if (RecordingPolicy.shouldAutoStop(ms)) {
                    stopReferenceCapture()
                    return
                }
                ui.postDelayed(this, HardFreeze.SIMULATED_STREAMING_CHUNK_MS)
            }
        }
        referenceTicker = r
        ui.post(r)
    }

    private fun stopReferenceTicker() {
        referenceTicker?.let { ui.removeCallbacks(it) }
        referenceTicker = null
    }

    private fun stopReferenceCapture() {
        if (!recordingReference) return
        stopReferenceTicker()
        recordingReference = false
        try {
            recorder.awaitStopped()
            val pcm = recorder.snapshotPcm16Bytes()
            if (pcm.isEmpty()) {
                setError("reference capture produced no audio")
            } else {
                refStore.save(pcm)
                showReference()
                setError("")
            }
        } catch (t: Throwable) {
            setError(t.message ?: t.toString())
        } finally {
            binding.recordReference.text = "RECORD REFERENCE"
            binding.startStop.isEnabled = true
            setStateLabel("IDLE")
        }
    }

    private fun onDeleteReference() {
        if (recordingReference || benchRunning) {
            setError("busy")
            return
        }
        refStore.delete()
        showReference()
    }

    private fun onRunThreadBenchmark() {
        startControlledJob {
            val audio = refStore.current ?: error("no reference audio")
            val samples = audio.toFloat32()
            val runner = ThreadBenchmarkRunner(
                decodeOnce = { s, sr -> backend.decode(s, sr) },
                ensureThreads = { n -> ensureRecognizerThreads(n) },
                memoryNow = { memory.snapshot() },
                peakAround = { block -> peakAroundDecode(block) },
            )
            val result = runner.run(
                samples = samples,
                sampleRate = audio.sampleRateHz,
                pcmSha256 = audio.sha256,
                audioMs = audio.durationMs,
                sampleCount = audio.sampleCount,
                byteCount = audio.byteCount,
                cancelled = { benchCancel.get() },
                progress = { done, total, label ->
                    postUi {
                        binding.benchProgress.text =
                            "THREAD BENCHMARK: run $done / $total\nMEMORY SOAK: idle\n$label"
                    }
                },
                thermalStart = ThermalProbe.status(this),
                thermalNow = { ThermalProbe.status(this) },
            )
            lastThreadResult = result.copy(
                resources = backend.counters.snapshot().copy(pendingDecode = decodeGate.pendingCount),
            )
        }
    }

    private fun onRunMemorySoak() {
        val iterations = selectedSoakIterations()
        val threads = selectedSoakThreads()
        startControlledJob {
            val audio = refStore.current ?: error("no reference audio")
            val samples = audio.toFloat32()
            val runner = MemorySoakRunner(
                decodeOnce = { s, sr -> backend.decode(s, sr) },
                ensureThreads = { n -> ensureRecognizerThreads(n) },
                memoryNow = { memory.snapshot() },
                countersNow = {
                    backend.counters.snapshot().copy(pendingDecode = decodeGate.pendingCount)
                },
            )
            val result = runner.run(
                samples = samples,
                sampleRate = audio.sampleRateHz,
                pcmSha256 = audio.sha256,
                audioMs = audio.durationMs,
                threads = threads,
                iterations = iterations,
                cancelled = { benchCancel.get() },
                progress = { done, total, label ->
                    postUi {
                        binding.benchProgress.text =
                            "THREAD BENCHMARK: idle\nMEMORY SOAK: iteration $done / $total\n$label"
                    }
                },
                thermalStart = ThermalProbe.status(this),
                thermalNow = { ThermalProbe.status(this) },
                persist = { benchJournal.markProgress(it) },
            )
            lastSoakResult = result.copy(
                resources = backend.counters.snapshot().copy(pendingDecode = decodeGate.pendingCount),
            )
        }
    }

    private fun startControlledJob(body: () -> Unit) {
        if (benchRunning || recordingReference || recorder.isRecording) {
            setError("busy")
            return
        }
        if (!backend.isReady) {
            setError("recognizer is not loaded")
            return
        }
        if (refStore.current == null) {
            setError("record reference audio first")
            return
        }
        benchCancel.set(false)
        benchRunning = true
        setControlledButtonsEnabled(false)
        setThreadControlsEnabled(false)
        setStateLabel("PROCESSING")
        worker.execute {
            try {
                body()
                restoreInteractiveRecognizer()
                val report = currentBenchmarkReport()
                benchJournal.complete(report)
                postUi {
                    binding.threadComparison.text =
                        ControlledBenchmarkReport.compactComparison(lastThreadResult)
                    binding.benchReport.text = report
                    showPreviousBenchmark()
                    setError("")
                    setStateLabel("COMPLETE")
                    binding.benchProgress.text =
                        "THREAD BENCHMARK: done\nMEMORY SOAK: done"
                }
            } catch (t: Throwable) {
                postUi {
                    setStateLabel("ERROR")
                    setError(t.message ?: t.toString())
                }
            } finally {
                benchRunning = false
                postUi {
                    setControlledButtonsEnabled(true)
                    setThreadControlsEnabled(true)
                }
            }
        }
    }

    private fun ensureRecognizerThreads(n: Int): Long {
        if (!RecognizerReuse.needsRecreate(backend.isReady, backend.loadedThreads, n)) return 0L
        val paths = ModelInstaller.ensureInstalled(this)
        val cfg = RecognitionConfigFactory.create(n, paths)
        val ms = backend.init(cfg)
        recognizerInitMs = ms
        return ms
    }

    private fun restoreInteractiveRecognizer() {
        try {
            ensureRecognizerThreads(selectedThreads().nThreads)
        } catch (_: Throwable) {
        }
    }

    private fun peakAroundDecode(block: () -> String): Pair<String, PeakMemory?> {
        val sampler = memory.startPeakSampler()
        return try {
            val text = block()
            text to sampler.stop()
        } catch (t: Throwable) {
            try {
                sampler.stop()
            } catch (_: Throwable) {
            }
            throw t
        }
    }

    private fun currentBenchmarkReport(): String = ControlledBenchmarkReport.render(
        modelName = BuildConfig.MODEL_NAME,
        modelHashes = ModelInstaller.fileSha256Block(),
        sherpaVersion = "${BuildConfig.SHERPA_ONNX_TAG} / ${BuildConfig.SHERPA_ONNX_COMMIT}",
        reference = refStore.current,
        thread = lastThreadResult,
        soak = lastSoakResult,
        pendingDecode = decodeGate.pendingCount,
        previousEndedAbnormally = benchJournal.previousEndedAbnormally,
        lastCompletedIteration = benchJournal.lastCompletedIteration,
        extraError = lastError,
    )

    private fun copyBenchmarkReport() {
        val text = currentBenchmarkReport()
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("CARFU Phase 3B.1.2 controlled benchmark", text))
        Toast.makeText(this, "Copied benchmark report", Toast.LENGTH_SHORT).show()
    }

    private fun setControlledButtonsEnabled(enabled: Boolean) {
        binding.recordReference.isEnabled = enabled
        binding.deleteReference.isEnabled = enabled
        binding.runThreadBenchmark.isEnabled = enabled
        binding.runMemorySoak.isEnabled = enabled
        binding.soakThreads1.isEnabled = enabled
        binding.soakThreads2.isEnabled = enabled
        binding.soakThreads4.isEnabled = enabled
        binding.soakIter10.isEnabled = enabled
        binding.soakIter30.isEnabled = enabled
        binding.soakIter50.isEnabled = enabled
        binding.startStop.isEnabled = enabled
    }

    private fun postUi(block: () -> Unit) {
        if (!alive) return
        ui.post {
            if (alive) block()
        }
    }

    override fun onPause() {
        journal.markLifecycle("MainActivity.onPause")
        super.onPause()
        if (recordingReference) {
            stopReferenceCapture()
        } else if (recorder.isRecording && !finalizeGuard.hasStarted) {
            stopAndFinalize(BenchmarkLimits.LIFECYCLE_STOP_REASON)
        }
    }

    override fun onDestroy() {
        journal.markLifecycle("MainActivity.onDestroy")
        alive = false
        benchCancel.set(true)
        stopTickers()
        stopReferenceTicker()
        if (recordingReference) {
            stopReferenceCapture()
        } else if (recorder.isRecording && !finalizeGuard.hasStarted) {
            stopAndFinalize(BenchmarkLimits.LIFECYCLE_STOP_REASON)
        }
        worker.execute {
            try {
                if (recorder.isRecording) recorder.stopAndFloatSamples()
            } catch (_: Throwable) {
            }
            recorder.release()
            try {
                peakSampler?.stop()
            } catch (_: Throwable) {
            }
            // Recognizer released only after queued decode/finalize work.
            backend.release()
        }
        worker.shutdown()
        try {
            worker.awaitTermination(20, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        sessions.cleanup()
        decoder = null
        super.onDestroy()
    }

    companion object {
        private const val REQ_RECORD = 92
        private const val REQ_RECORD_REF = 93
    }
}
