package org.stypox.dicio.io.session

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class CommandUiState(
    val phase: CommandSessionPhase = CommandSessionPhase.IDLE_WAKE,
    val lastHeard: String? = null,
    val lastReply: String? = null,
    val partial: String? = null,
    val unclear: Boolean = false,
    val sessionId: Long = 0,
    val elapsedMs: Long = 0,
    val captureRateHz: Int = AudioCaptureConfig.MODEL_RATE_HZ,
    val modelPath: String? = null,
)

/**
 * Coordinates wake-word vs command STT, audio focus, and driving-screen UI state.
 */
@Singleton
class CommandSession @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
) {
    private val machine = CommandSessionMachine()
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private val focusListener = AudioManager.OnAudioFocusChangeListener { }

    private val _ui = MutableStateFlow(CommandUiState())
    val ui: StateFlow<CommandUiState> = _ui

    val phase: CommandSessionPhase get() = machine.phase
    val isBusy: Boolean get() = machine.isBusy
    val elapsedMs: Long get() = machine.elapsedMs
    val activationOrigin: CarfuActivationSource.Kind get() = machine.activationOrigin

    fun tryBeginWakeSession(
        origin: CarfuActivationSource.Kind = CarfuActivationSource.Kind.AUTOMATIC_WAKE,
    ): Boolean {
        val started = machine.onWakeDetected(origin)
        if (!started) {
            log("COMMAND_SESSION_OVERLAP ignored elapsed=${machine.elapsedMs}")
            return false
        }
        publish()
        log("COMMAND_SESSION_START id=${machine.sessionId} origin=$origin")
        log("WAKE_PCM_ROUTE=discard recorder_held=true")
        return true
    }

    fun onTtsStarted() {
        machine.onTtsStarted()
        publish()
        log("TTS_STARTED")
    }

    fun onTtsCompleted() {
        machine.onTtsCompleted()
        publish()
        log("TTS_COMPLETED elapsed=${machine.elapsedMs}")
    }

    fun onCommandAudioStarted(
        sampleRate: Int,
        bufferSize: Int,
        audioSource: Int,
        modelPath: String?,
        needsResample: Boolean,
    ) {
        machine.onCommandAudioStarted()
        requestTransientFocus()
        _ui.value = _ui.value.copy(
            phase = machine.phase,
            captureRateHz = sampleRate,
            modelPath = modelPath,
            partial = null,
            elapsedMs = machine.elapsedMs,
        )
        log(
            "COMMAND_AUDIO_STARTED sampleRate=$sampleRate bufferSize=$bufferSize " +
                "audioSource=$audioSource resample=$needsResample model=$modelPath"
        )
    }

    fun onSpeechBegin() {
        log("SPEECH_BEGIN")
    }

    fun onPartial(text: String) {
        _ui.value = _ui.value.copy(partial = text, elapsedMs = machine.elapsedMs)
        log("PARTIAL_TEXT text=${text.take(80)}")
    }

    fun onFinalText(original: String) {
        machine.onProcessing()
        _ui.value = _ui.value.copy(
            phase = machine.phase,
            lastHeard = original,
            partial = null,
            unclear = false,
            elapsedMs = machine.elapsedMs,
        )
        log("FINAL_TEXT text=${original.take(80)}")
        log("SPEECH_END")
    }

    fun onIntentMatch(skillId: String) {
        log("INTENT_MATCH skill=$skillId")
    }

    fun onReply(text: String) {
        machine.onResponding()
        _ui.value = _ui.value.copy(
            phase = machine.phase,
            lastReply = text,
            unclear = false,
            elapsedMs = machine.elapsedMs,
        )
    }

    fun onUnclear() {
        machine.onResponding()
        _ui.value = _ui.value.copy(
            phase = machine.phase,
            lastReply = null,
            unclear = true,
            elapsedMs = machine.elapsedMs,
        )
    }

    fun endSession(reason: String) {
        val elapsed = machine.elapsedMs
        machine.onReturningToWake()
        abandonFocus()
        log("COMMAND_SESSION_END reason=$reason elapsedMs=$elapsed")
        machine.onIdle()
        _ui.value = _ui.value.copy(
            phase = CommandSessionPhase.IDLE_WAKE,
            partial = null,
            elapsedMs = 0,
        )
        log("WAKE_RESUME_SCHEDULED")
    }

    fun canStartCommandRecognition(): Boolean = machine.canStartCommandRecognition()

    private fun requestTransientFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener(focusListener)
                    .setWillPauseWhenDucked(false)
                    .build()
                focusRequest = req
                audioManager.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    focusListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                )
            }
        } catch (t: Throwable) {
            log("error reason=audio_focus ${t.javaClass.simpleName} elapsed=${machine.elapsedMs}")
        }
    }

    private fun abandonFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(focusListener)
            }
        } catch (_: Throwable) {
        }
        focusRequest = null
    }

    private fun publish() {
        _ui.value = _ui.value.copy(
            phase = machine.phase,
            sessionId = machine.sessionId,
            elapsedMs = machine.elapsedMs,
        )
    }

    private fun log(message: String) {
        CarfuLog.i(TAG, "session=${machine.sessionId} phase=${machine.phase} $message")
    }

    companion object {
        const val TAG = "CarfuCommand"
        const val POST_TTS_GUARD_MS = 450L
        const val COMMAND_LISTEN_TIMEOUT_MS = 10_000
        const val DEFAULT_SILENCES_BEFORE_STOP = 5
        const val COMMAND_ENDPOINT_GRACE_MS = 2_500L
    }
}
