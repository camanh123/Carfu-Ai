package org.stypox.dicio.io.session

import org.stypox.dicio.io.session.CommandSessionPhase.ACKNOWLEDGING
import org.stypox.dicio.io.session.CommandSessionPhase.COMMAND_LISTENING
import org.stypox.dicio.io.session.CommandSessionPhase.IDLE_WAKE
import org.stypox.dicio.io.session.CommandSessionPhase.PROCESSING
import org.stypox.dicio.io.session.CommandSessionPhase.RESPONDING
import org.stypox.dicio.io.session.CommandSessionPhase.RETURNING_TO_WAKE
import org.stypox.dicio.io.session.CommandSessionPhase.WAKE_DETECTED

/**
 * Pure state machine for command sessions. No Android types so JVM tests can drive it.
 *
 * Command recognition is allowed only after the wake acknowledgment TTS has reported completion.
 */
class CommandSessionMachine(
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    @Volatile
    var phase: CommandSessionPhase = IDLE_WAKE
        private set

    @Volatile
    var activationOrigin: CarfuActivationSource.Kind = CarfuActivationSource.Kind.AUTOMATIC_WAKE

    var sessionId: Long = 0
        private set

    var startedAtMs: Long = 0
        private set

    var ttsCompleted: Boolean = false
        private set

    val isBusy: Boolean
        get() = phase != IDLE_WAKE

    val elapsedMs: Long
        get() = if (startedAtMs == 0L) 0L else clockMs() - startedAtMs

    /**
     * @return false if a session is already running (overlapping wake detections are ignored)
     */
    fun onWakeDetected(
        origin: CarfuActivationSource.Kind = CarfuActivationSource.Kind.AUTOMATIC_WAKE,
    ): Boolean {
        if (isBusy) {
            return false
        }
        sessionId += 1
        startedAtMs = clockMs()
        ttsCompleted = false
        activationOrigin = origin
        phase = WAKE_DETECTED
        return true
    }

    fun onTtsStarted() {
        ttsCompleted = false
        when (phase) {
            WAKE_DETECTED, IDLE_WAKE -> phase = ACKNOWLEDGING
            PROCESSING, COMMAND_LISTENING -> phase = RESPONDING
            else -> {}
        }
    }

    fun onTtsCompleted() {
        ttsCompleted = true
        if (phase == ACKNOWLEDGING || phase == WAKE_DETECTED) {
            phase = ACKNOWLEDGING
        }
    }

    fun onCommandAudioStarted() {
        if (phase == ACKNOWLEDGING ||
            phase == WAKE_DETECTED ||
            phase == PROCESSING ||
            phase == RESPONDING
        ) {
            phase = COMMAND_LISTENING
        }
    }

    fun onProcessing() {
        if (phase == COMMAND_LISTENING || phase == ACKNOWLEDGING) {
            phase = PROCESSING
        }
    }

    fun onResponding() {
        if (phase == PROCESSING || phase == COMMAND_LISTENING) {
            phase = RESPONDING
        }
    }

    fun onReturningToWake() {
        phase = RETURNING_TO_WAKE
    }

    fun onIdle() {
        phase = IDLE_WAKE
        startedAtMs = 0
        ttsCompleted = false
    }

    /**
     * True only after the current utterance's TTS onDone, while still in the post-ack window
     * (or while reopening the mic after a spoken reply).
     */
    fun canStartCommandRecognition(): Boolean {
        return ttsCompleted && (
            phase == ACKNOWLEDGING ||
                phase == RESPONDING ||
                phase == PROCESSING
            )
    }
}
