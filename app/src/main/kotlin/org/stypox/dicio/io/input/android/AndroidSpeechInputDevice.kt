package org.stypox.dicio.io.input.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.stypox.dicio.io.input.CommandRecognitionPolicy
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.io.input.SttInputDevice
import org.stypox.dicio.io.input.SttState
import org.stypox.dicio.io.session.CarfuLatencyLog
import org.stypox.dicio.io.session.CarfuLog
import org.stypox.dicio.io.session.CarfuPcmHub
import org.stypox.dicio.io.session.CommandSession
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * In-process Android/Google [SpeechRecognizer] for Vietnamese commands.
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
    private val timeoutRunnable = Runnable {
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
            cancelTimeout()
            val sr = recognizer.getAndSet(null)
            if (sr != null) {
                try {
                    sr.cancel()
                } catch (_: Throwable) {
                }
                destroyRecognizer(sr)
            }
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
            cancelTimeout()
            val sr = recognizer.getAndSet(null)
            if (sr != null) destroyRecognizer(sr)
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
        if (CarfuPcmHub.isRecording()) {
            CarfuLog.e(CommandSession.TAG, "ANDROID_SR_REFUSED hub_still_recording=true")
            return false
        }
        stopListeningInternal()
        terminalEmitted.set(false)
        listenerRef.set(eventListener)
        val sr = try {
            SpeechRecognizer.createSpeechRecognizer(
                context,
                ComponentName(component.packageName, component.className),
            )
        } catch (t: Throwable) {
            CarfuLog.e(CommandSession.TAG, "ANDROID_SR_CREATE_FAILED ${t.javaClass.simpleName}")
            _uiState.value = SttState.NotAvailable
            listenerRef.set(null)
            return false
        }
        recognizer.set(sr)
        sr.setRecognitionListener(Listener())
        val intent = recognizerIntent()
        CarfuLatencyLog.nowMs = { SystemClock.elapsedRealtime() }
        CarfuLatencyLog.mark(CarfuLatencyLog.Mark.SR_START_LISTENING)
        CarfuLog.i(
            CommandSession.TAG,
            "ANDROID_SR_START package=${component.packageName} " +
                "class=${component.className} language=vi-VN popup=false browser=false",
        )
        try {
            sr.startListening(intent)
        } catch (t: Throwable) {
            CarfuLog.e(CommandSession.TAG, "ANDROID_SR_START_FAILED ${t.javaClass.simpleName}")
            destroyRecognizer(sr)
            recognizer.set(null)
            listenerRef.set(null)
            return false
        }
        _uiState.value = SttState.Listening
        mainHandler.postDelayed(timeoutRunnable, CommandRecognitionPolicy.ANDROID_LISTEN_TIMEOUT_MS)
        return true
    }

    private fun recognizerIntent(): Intent {
        val cfg = CommandRecognitionPolicy.recognizerIntentConfig()
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
        cancelTimeout()
        val sr = recognizer.getAndSet(null)
        if (sr != null) {
            try {
                sr.cancel()
            } catch (_: Throwable) {
            }
            destroyRecognizer(sr)
        }
    }

    private fun destroyRecognizer(sr: SpeechRecognizer) {
        try {
            sr.destroy()
        } catch (_: Throwable) {
        }
        CarfuLog.i(CommandSession.TAG, "ANDROID_SR_DESTROYED")
    }

    private fun cancelTimeout() {
        mainHandler.removeCallbacks(timeoutRunnable)
    }

    private fun onTerminal(
        event: CommandRecognitionPolicy.RecognizerTerminal,
        emit: (((InputEvent) -> Unit) -> Unit),
    ) {
        if (!terminalEmitted.compareAndSet(false, true)) return
        if (!CommandRecognitionPolicy.shouldDestroyRecognizerOn(event)) return
        cancelTimeout()
        val listener = listenerRef.getAndSet(null)
        val sr = recognizer.getAndSet(null)
        if (sr != null) destroyRecognizer(sr)
        if (_uiState.value != SttState.NotAvailable) {
            _uiState.value = SttState.Loaded
        }
        CarfuLatencyLog.mark(CarfuLatencyLog.Mark.FINAL_OR_ERROR)
        if (listener != null) {
            emit(listener)
        }
    }

    private inner class Listener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            CarfuLatencyLog.mark(CarfuLatencyLog.Mark.SR_READY)
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            CarfuLog.i(CommandSession.TAG, "ANDROID_SR_ERROR code=$error")
            onTerminal(CommandRecognitionPolicy.RecognizerTerminal.ERROR) { listener ->
                if (CommandRecognitionPolicy.isNoSpeechError(error)) {
                    listener(InputEvent.None)
                } else {
                    listener(InputEvent.Error(AndroidSpeechError(error)))
                }
            }
        }

        override fun onResults(results: Bundle?) {
            val utterances = utterancesFrom(results)
            onTerminal(CommandRecognitionPolicy.RecognizerTerminal.RESULT) { listener ->
                if (utterances.isEmpty()) {
                    listener(InputEvent.None)
                } else {
                    listener(InputEvent.Final(utterances))
                }
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = utterancesFrom(partialResults).firstOrNull()?.first ?: return
            if (text.isBlank()) return
            CarfuLatencyLog.mark(CarfuLatencyLog.Mark.FIRST_PARTIAL)
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
