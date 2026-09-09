package org.stypox.dicio.io.input.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.stypox.dicio.io.input.CommandRecognitionPolicy
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.io.input.SpeechRecognizerSessionPolicy
import org.stypox.dicio.io.input.SttInputDevice
import org.stypox.dicio.io.input.SttState
import org.stypox.dicio.io.session.CarfuLatencyLog
import org.stypox.dicio.io.session.CarfuLog
import org.stypox.dicio.io.session.CarfuPcmHub
import org.stypox.dicio.io.session.CarfuVoiceTrace
import org.stypox.dicio.io.session.CommandSession
import org.stypox.dicio.io.session.RecordAudioPermission
import org.stypox.dicio.io.session.VoiceSessionManager
import org.stypox.dicio.io.session.VoiceToActionLatency
import org.stypox.dicio.io.session.VoiceToActionLatencyPolicy
import org.stypox.dicio.io.session.VoiceToActionStage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * In-process Android/Google [SpeechRecognizer] for Vietnamese commands.
 *
 * Known-good listener spine (81aa-shaped):
 * - exactly one create/startListening cycle per session arm
 * - no SR_REARM / multi-start recovery (MAX_SR_REARMS = 0)
 * - BOS/EOS are logged only; they do not own session lifetime
 * - SpeechRecognizer callbacks are events; the product silence timeout owns no-speech
 * - stale listeners (wrong generation) cannot terminate a new session
 *
 * Phase 4.3A: SpeechRecognizer callbacks also stamp [VoiceToActionLatency]
 * stages (ready / BOS / EOS / partial / final). That is instrumentation only —
 * it does not change listener ownership or execute from partials.
 *
 * Never starts a recognizer Activity or a browser search. Never binds this
 * app's own [org.stypox.dicio.io.input.stt_service.SttService].
 */
class AndroidSpeechInputDevice(
    @param:ApplicationContext private val context: Context,
) : SttInputDevice {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val recognizer = AtomicReference<SpeechRecognizer?>(null)
    private val listenerRef = AtomicReference<((InputEvent) -> Unit)?>(null)
    private val destroyed = AtomicBoolean(false)
    private val terminalEmitted = AtomicBoolean(false)
    private val listenerGeneration = AtomicLong(0L)
    private val armStartedAtMs = AtomicLong(0L)
    private val sawReady = AtomicBoolean(false)
    private val sawSpeechOrPartial = AtomicBoolean(false)

    override fun currentRecognizerGeneration(): Long = listenerGeneration.get()

    private val hardListenTimeoutRunnable = Runnable {
        CarfuLatencyLog.logSessionEvent("SR_TIMEOUT", "kind=HARD_LISTEN_CEILING")
        CarfuLatencyLog.logPipelineStage("SR_TIMEOUT")
        CarfuVoiceTrace.event("SR_HARD_CEILING")
        onTerminal(CommandRecognitionPolicy.RecognizerTerminal.TIMEOUT) {
            it(InputEvent.None)
        }
    }

    private val _uiState = MutableStateFlow(initialState())
    override val uiState: StateFlow<SttState> = _uiState

    fun isRecognizerReady(): Boolean {
        val state = _uiState.value
        return state == SttState.Loaded || state == SttState.Listening
    }

    override fun tryLoad(thenStartListeningEventListener: ((InputEvent) -> Unit)?): Boolean {
        if (_uiState.value == SttState.NotAvailable) return false
        if (thenStartListeningEventListener == null) {
            return isRecognizerReady() || refreshAvailability()
        }
        return startListening(thenStartListeningEventListener)
    }

    override fun stopListening() {
        runOnMain {
            CarfuVoiceTrace.stopRequest("AndroidSpeechInputDevice.stopListening")
            retireRecognizer(invalidateListener = true)
            listenerRef.set(null)
            if (_uiState.value == SttState.Listening) {
                _uiState.value = SttState.Loaded
            }
        }
    }

    override fun onClick(eventListener: (InputEvent) -> Unit) {
        startListening(eventListener)
    }

    override suspend fun destroy() {
        destroyed.set(true)
        runOnMainBlocking {
            CarfuVoiceTrace.stopRequest("AndroidSpeechInputDevice.destroy")
            retireRecognizer(invalidateListener = true)
            listenerRef.set(null)
        }
    }

    private fun refreshAvailability(): Boolean {
        val available = pickExternalService() != null
        _uiState.value = if (available) SttState.Loaded else SttState.NotAvailable
        return available
    }

    private fun initialState(): SttState {
        return if (pickExternalService() != null) SttState.Loaded else SttState.NotAvailable
    }

    private fun startListening(eventListener: (InputEvent) -> Unit): Boolean {
        if (destroyed.get()) return false
        if (CarfuPcmHub.isRecording() ||
            !CommandRecognitionPolicy.canStartAndroidRecognizer(CarfuPcmHub.isRecording())
        ) {
            CarfuLog.e(
                CommandSession.TAG,
                "ANDROID_SR_REFUSED hub_recording=${CarfuPcmHub.isRecording()}",
            )
            CarfuVoiceTrace.permissionOrAvailability("hub_recording")
            return false
        }
        val component = pickExternalService()
        if (component == null) {
            _uiState.value = SttState.NotAvailable
            return false
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return startListeningOnMain(eventListener, component)
        }
        var started = false
        val lock = Object()
        mainHandler.post {
            started = startListeningOnMain(eventListener, component)
            synchronized(lock) { lock.notifyAll() }
        }
        synchronized(lock) {
            lock.wait(1_000L)
        }
        return started
    }

    private fun startListeningOnMain(
        eventListener: (InputEvent) -> Unit,
        component: CommandRecognitionPolicy.RecognitionServiceCandidate,
    ): Boolean {
        if (destroyed.get()) return false
        val perm = RecordAudioPermission.logForVoiceTrigger(context, "startListeningOnMain")
        if (!perm.mayStartSpeechRecognizer()) {
            CarfuLog.e(
                CommandSession.TAG,
                "ANDROID_SR_REFUSED ${perm.runtimeLabel()} " +
                    "manifest=${perm.manifestLabel()}",
            )
            return false
        }
        val recognitionAvailable = try {
            SpeechRecognizer.isRecognitionAvailable(context)
        } catch (_: Throwable) {
            false
        }
        if (!recognitionAvailable) {
            CarfuLog.e(CommandSession.TAG, "ANDROID_SR_REFUSED recognition_unavailable")
            CarfuVoiceTrace.permissionOrAvailability("recognition_unavailable")
            _uiState.value = SttState.NotAvailable
            return false
        }
        if (CarfuPcmHub.isRecording()) {
            CarfuLog.e(CommandSession.TAG, "ANDROID_SR_REFUSED hub_still_recording=true")
            CarfuVoiceTrace.permissionOrAvailability("hub_still_recording")
            return false
        }
        stopListeningInternal()
        terminalEmitted.set(false)
        sawReady.set(false)
        sawSpeechOrPartial.set(false)
        listenerRef.set(eventListener)
        val generation = listenerGeneration.incrementAndGet()
        val sr = try {
            SpeechRecognizer.createSpeechRecognizer(
                context,
                ComponentName(component.packageName, component.className),
            )
        } catch (t: Throwable) {
            CarfuLog.e(CommandSession.TAG, "ANDROID_SR_CREATE_FAILED ${t.javaClass.simpleName}")
            CarfuVoiceTrace.permissionOrAvailability("create_failed_${t.javaClass.simpleName}")
            _uiState.value = SttState.NotAvailable
            listenerRef.set(null)
            return false
        }
        recognizer.set(sr)
        CarfuLatencyLog.logPipelineStage("SR_CREATE")
        CarfuVoiceTrace.srCreate(component.packageName, component.className)
        sr.setRecognitionListener(Listener(generation))
        val intent = recognizerIntent()
        VoiceToActionLatency.mark(
            VoiceToActionStage.SR_INTENT_CONFIG,
            VoiceToActionLatencyPolicy.recognizerSilenceExtrasLog(),
        )
        CarfuLatencyLog.nowMs = { SystemClock.elapsedRealtime() }
        CarfuLatencyLog.mark(CarfuLatencyLog.Mark.SR_START_LISTENING)
        CarfuLatencyLog.logPipelineStage("SR_START_LISTENING")
        CarfuVoiceTrace.srStartListening()
        armStartedAtMs.set(SystemClock.elapsedRealtime())
        CarfuLog.i(
            CommandSession.TAG,
            "ANDROID_SR_START package=${component.packageName} " +
                "class=${component.className} language=vi-VN popup=false browser=false " +
                "gen=$generation",
        )
        try {
            sr.startListening(intent)
        } catch (t: Throwable) {
            CarfuLog.e(CommandSession.TAG, "ANDROID_SR_START_FAILED ${t.javaClass.simpleName}")
            CarfuVoiceTrace.permissionOrAvailability("start_failed_${t.javaClass.simpleName}")
            listenerGeneration.incrementAndGet()
            destroyRecognizer(sr)
            recognizer.set(null)
            listenerRef.set(null)
            return false
        }
        _uiState.value = SttState.Listening
        mainHandler.postDelayed(
            hardListenTimeoutRunnable,
            CommandRecognitionPolicy.ANDROID_LISTEN_TIMEOUT_MS,
        )
        return true
    }

    private fun recognizerIntent(): Intent {
        val cfg = CommandRecognitionPolicy.recognizerIntentConfig()
        // OEM-sensitive: do NOT put EXTRA_SPEECH_INPUT_* silence/minimum extras.
        // Policy constants exist for Smart helpers only; unset → OEM endpointer defaults.
        return Intent(cfg.action).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, cfg.languageModel)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, cfg.language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, cfg.language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, cfg.partialResults)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, cfg.maxResults)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, cfg.preferOffline)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
    }

    private fun pickExternalService(): CommandRecognitionPolicy.RecognitionServiceCandidate? {
        val intent = Intent("android.speech.RecognitionService")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PackageManager.MATCH_ALL
        } else {
            0
        }
        val resolved = try {
            context.packageManager.queryIntentServices(intent, flags)
        } catch (_: Throwable) {
            emptyList()
        }
        val candidates = resolved.mapNotNull { info ->
            val service = info.serviceInfo ?: return@mapNotNull null
            CommandRecognitionPolicy.RecognitionServiceCandidate(
                packageName = service.packageName,
                className = service.name,
            )
        }
        return CommandRecognitionPolicy.pickExternalRecognitionService(
            context.packageName,
            candidates,
        )
    }

    private fun stopListeningInternal() {
        retireRecognizer(invalidateListener = true, cancelFirst = true)
    }

    private fun retireRecognizer(
        invalidateListener: Boolean,
        cancelFirst: Boolean = true,
    ) {
        cancelTimeout()
        if (invalidateListener) {
            listenerGeneration.incrementAndGet()
        }
        val sr = recognizer.getAndSet(null)
        if (sr != null) {
            if (cancelFirst) {
                CarfuLatencyLog.logPipelineStage("SR_CANCEL")
                try {
                    sr.cancel()
                } catch (_: Throwable) {
                }
            }
            destroyRecognizer(sr)
        }
    }

    private fun destroyRecognizer(sr: SpeechRecognizer) {
        try {
            sr.destroy()
        } catch (_: Throwable) {
        }
        CarfuLatencyLog.logPipelineStage("SR_DESTROY")
        CarfuLog.i(CommandSession.TAG, "ANDROID_SR_DESTROYED")
    }

    private fun cancelTimeout() {
        mainHandler.removeCallbacks(hardListenTimeoutRunnable)
    }

    private fun armElapsedMs(): Long {
        val started = armStartedAtMs.get()
        if (started <= 0L) return 0L
        return SystemClock.elapsedRealtime() - started
    }

    /**
     * Recognizer instance ended, but the product session stays LISTENING until
     * the 5s silence watch or a later unrecoverable/final event.
     */
    private fun absorbRecognizerEnd(reason: String) {
        CarfuVoiceTrace.srAbsorbed(reason)
        retireRecognizer(invalidateListener = true, cancelFirst = false)
        if (_uiState.value != SttState.NotAvailable && _uiState.value != SttState.Loaded) {
            _uiState.value = SttState.Loaded
        }
    }

    private fun onTerminal(
        event: CommandRecognitionPolicy.RecognizerTerminal,
        emit: (((InputEvent) -> Unit) -> Unit),
    ) {
        if (!terminalEmitted.compareAndSet(false, true)) return
        if (!CommandRecognitionPolicy.shouldDestroyRecognizerOn(event)) return
        retireRecognizer(invalidateListener = true, cancelFirst = false)
        if (_uiState.value != SttState.NotAvailable) {
            _uiState.value = SttState.Loaded
        }
        CarfuLatencyLog.mark(CarfuLatencyLog.Mark.FINAL_OR_ERROR)
        val listener = listenerRef.get()
        if (listener != null) {
            emit(listener)
        }
    }

    private inner class Listener(private val generation: Long) : RecognitionListener {
        private fun isCurrent(callback: String): Boolean {
            val current = listenerGeneration.get()
            if (generation != current) {
                CarfuVoiceTrace.staleCallback(callback, generation, current)
                return false
            }
            return true
        }

        override fun onReadyForSpeech(params: Bundle?) {
            if (!isCurrent("onReadyForSpeech")) return
            sawReady.set(true)
            CarfuLatencyLog.mark(CarfuLatencyLog.Mark.SR_READY)
            CarfuLatencyLog.logPipelineStage("SR_READY")
            CarfuVoiceTrace.srReady()
            VoiceToActionLatency.mark(VoiceToActionStage.LISTENING_READY, "sr_onReadyForSpeech")
        }

        override fun onBeginningOfSpeech() {
            if (!isCurrent("onBeginningOfSpeech")) return
            sawSpeechOrPartial.set(true)
            // Acoustic only — does not cancel the hard listen ceiling or re-arm.
            CarfuLatencyLog.mark(CarfuLatencyLog.Mark.BEGINNING_OF_SPEECH)
            CarfuLatencyLog.logPipelineStage("SR_BEGIN")
            CarfuVoiceTrace.srBeginSpeech()
            VoiceToActionLatency.mark(VoiceToActionStage.FIRST_SPEECH, "sr_onBeginningOfSpeech")
        }

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            if (!isCurrent("onEndOfSpeech")) return
            // Logged only — must not terminate the product session.
            // Do NOT call SpeechRecognizer.stopListening() here: EOS means the OEM
            // endpointer already stopped capture. Remaining delay is remote/final
            // processing. stopListening after EOS is unproven and OEM-unsafe.
            // Do NOT cancel()/destroy() here either — that would abort onResults.
            CarfuLatencyLog.mark(CarfuLatencyLog.Mark.END_OF_SPEECH)
            CarfuLatencyLog.logPipelineStage("SR_END_OF_SPEECH")
            CarfuVoiceTrace.srEndSpeech()
            VoiceToActionLatency.mark(VoiceToActionStage.LAST_SPEECH, "sr_onEndOfSpeech")
            if (SpeechRecognizerSessionPolicy.endOfSpeechTerminatesProduct()) {
                onTerminal(CommandRecognitionPolicy.RecognizerTerminal.ERROR) {
                    it(InputEvent.None)
                }
            } else {
                // Acoustic only — product session stays LISTENING. SkillEvaluator may
                // use this as a stability signal; it must not cancel the 5s watch.
                listenerRef.get()?.invoke(InputEvent.EndOfSpeech)
            }
        }

        override fun onError(error: Int) {
            val current = listenerGeneration.get()
            val name = SpeechRecognizerSessionPolicy.errorName(error)
            val action = SpeechRecognizerSessionPolicy.onError(
                code = error,
                generationMatches = generation == current,
                sawReady = sawReady.get(),
                sawSpeechOrPartial = sawSpeechOrPartial.get(),
                elapsedMs = armElapsedMs(),
                productTimeoutMs = VoiceSessionManager.NO_SPEECH_TIMEOUT_MS,
            )
            CarfuLog.i(CommandSession.TAG, "ANDROID_SR_ERROR code=$error name=$name action=$action")
            CarfuLatencyLog.logPipelineStage("SR_ERROR", "code=$error name=$name")
            CarfuVoiceTrace.srError(error, name, action.name, generation, current)
            when (action) {
                SpeechRecognizerSessionPolicy.ProductAction.IGNORE_STALE -> return
                SpeechRecognizerSessionPolicy.ProductAction.KEEP_PRODUCT_SESSION -> {
                    absorbRecognizerEnd("sr_error_$name")
                }
                SpeechRecognizerSessionPolicy.ProductAction.TERMINATE_NO_SPEECH -> {
                    onTerminal(CommandRecognitionPolicy.RecognizerTerminal.ERROR) { listener ->
                        listener(InputEvent.None)
                    }
                }
                SpeechRecognizerSessionPolicy.ProductAction.TERMINATE_UNRECOVERABLE -> {
                    onTerminal(CommandRecognitionPolicy.RecognizerTerminal.ERROR) { listener ->
                        listener(InputEvent.Error(AndroidSpeechError(error)))
                    }
                }
                SpeechRecognizerSessionPolicy.ProductAction.PROCESS_FINAL -> {
                    onTerminal(CommandRecognitionPolicy.RecognizerTerminal.ERROR) { listener ->
                        listener(InputEvent.None)
                    }
                }
            }
        }

        override fun onResults(results: Bundle?) {
            val current = listenerGeneration.get()
            val utterances = utterancesFrom(results)
            val action = SpeechRecognizerSessionPolicy.onResults(
                utteranceCount = utterances.size,
                generationMatches = generation == current,
                sawSpeechOrPartial = sawSpeechOrPartial.get(),
                elapsedMs = armElapsedMs(),
                productTimeoutMs = VoiceSessionManager.NO_SPEECH_TIMEOUT_MS,
            )
            CarfuLatencyLog.logSessionEvent(
                "SR_RESULTS",
                "candidates=${utterances.size} action=$action",
            )
            CarfuLatencyLog.logPipelineStage(
                "SR_RESULTS",
                "candidates=${utterances.size}",
            )
            when (action) {
                SpeechRecognizerSessionPolicy.ProductAction.IGNORE_STALE -> {
                    CarfuVoiceTrace.staleCallback("onResults", generation, current)
                }
                SpeechRecognizerSessionPolicy.ProductAction.KEEP_PRODUCT_SESSION -> {
                    CarfuVoiceTrace.srFinal("", 0)
                    absorbRecognizerEnd("empty_results_before_speech")
                }
                SpeechRecognizerSessionPolicy.ProductAction.PROCESS_FINAL -> {
                    val text = utterances.firstOrNull()?.first.orEmpty()
                    CarfuVoiceTrace.srFinal(text, utterances.size)
                    VoiceToActionLatency.mark(
                        VoiceToActionStage.FINAL_TRANSCRIPT,
                        "candidates=${utterances.size} text=${text.trim().take(80)}",
                    )
                    onTerminal(CommandRecognitionPolicy.RecognizerTerminal.RESULT) { listener ->
                        listener(InputEvent.Final(utterances))
                    }
                }
                SpeechRecognizerSessionPolicy.ProductAction.TERMINATE_NO_SPEECH -> {
                    CarfuVoiceTrace.srFinal("", utterances.size)
                    onTerminal(CommandRecognitionPolicy.RecognizerTerminal.RESULT) { listener ->
                        listener(InputEvent.None)
                    }
                }
                SpeechRecognizerSessionPolicy.ProductAction.TERMINATE_UNRECOVERABLE -> {
                    onTerminal(CommandRecognitionPolicy.RecognizerTerminal.RESULT) { listener ->
                        listener(InputEvent.None)
                    }
                }
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = utterancesFrom(partialResults).firstOrNull()?.first ?: return
            val current = listenerGeneration.get()
            if (!SpeechRecognizerSessionPolicy.onPartial(
                    generationMatches = generation == current,
                    textBlank = text.isBlank(),
                )
            ) {
                if (generation != current) {
                    CarfuVoiceTrace.staleCallback("onPartialResults", generation, current)
                }
                return
            }
            sawSpeechOrPartial.set(true)
            CarfuLatencyLog.mark(CarfuLatencyLog.Mark.PARTIAL_RESULT)
            CarfuLatencyLog.logPipelineStage("SR_PARTIAL", "len=${text.length}")
            CarfuVoiceTrace.srPartial(text)
            VoiceToActionLatency.mark(
                VoiceToActionStage.PARTIAL_TRANSCRIPT,
                "len=${text.length} text=${text.trim().take(80)}",
            )
            // Visible transcript / ranking only — never terminates the SR session.
            listenerRef.get()?.invoke(InputEvent.Partial(text))
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun utterancesFrom(bundle: Bundle?): List<Pair<String, Float>> {
        if (bundle == null) return emptyList()
        val results = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?: return emptyList()
        val confidences = bundle.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
        return CommandRecognitionPolicy.finalUtterances(
            results,
            confidences?.toList(),
        )
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun runOnMainBlocking(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        val lock = Object()
        var done = false
        mainHandler.post {
            try {
                block()
            } finally {
                synchronized(lock) {
                    done = true
                    lock.notifyAll()
                }
            }
        }
        synchronized(lock) {
            if (!done) lock.wait(1_000L)
        }
    }

    class AndroidSpeechError(val code: Int) : RuntimeException("SpeechRecognizer error $code")

    companion object {
        fun isExternalRecognitionAvailable(context: Context): Boolean {
            return AndroidSpeechInputDevice(context).isRecognizerReady()
        }
    }
}
