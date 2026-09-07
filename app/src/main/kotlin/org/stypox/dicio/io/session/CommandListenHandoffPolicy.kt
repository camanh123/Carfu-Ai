package org.stypox.dicio.io.session

import org.stypox.dicio.io.input.CommandRecognitionPolicy

/**
 * Legacy handoff / pre-speech helpers retained for Smart-layer tests and documentation.
 *
 * Runtime microphone ownership no longer uses re-arm, absolute 3s no-speech budgets,
 * or multi-phase handoff. [AndroidSpeechInputDevice] is the single listener owner.
 */
enum class CommandListenPhase {
    IDLE,
    ACK_PLAYING,
    MIC_HANDOFF,
    WAITING_FOR_USER_SPEECH,
    USER_SPEECH_ACTIVE,
    WAITING_FOR_ENDPOINT,
    TERMINAL,
}

object CommandListenHandoffPolicy {
    /** Disabled — listener uses a single post-ACK echo guard only. */
    const val MIC_HANDOFF_MS = CommandRecognitionPolicy.ANDROID_MIC_HANDOFF_MS
    /** Re-arm is permanently disabled in the restored known-good listener. */
    const val MAX_PRE_SPEECH_REARMS = CommandRecognitionPolicy.MAX_SR_REARMS
    /** App-side 3s no-speech budget removed; Google + 12s hard ceiling only. */
    const val NO_SPEECH_BUDGET_MS = CommandRecognitionPolicy.ANDROID_NO_SPEECH_AFTER_READY_MS

    fun acceptLifecycleCallback(
        phase: CommandListenPhase,
        handoffComplete: Boolean,
    ): Boolean {
        if (!handoffComplete) return false
        return when (phase) {
            CommandListenPhase.WAITING_FOR_USER_SPEECH,
            CommandListenPhase.USER_SPEECH_ACTIVE,
            CommandListenPhase.WAITING_FOR_ENDPOINT,
            -> true
            else -> false
        }
    }

    fun markConfirmedUserSpeech(
        handoffComplete: Boolean,
        nonEmptyPartial: Boolean,
        nonEmptyFinal: Boolean,
    ): Boolean {
        if (!handoffComplete) return false
        return nonEmptyPartial || nonEmptyFinal
    }

    fun shouldIgnorePreSpeechEos(
        handoffComplete: Boolean,
        confirmedUserSpeech: Boolean,
    ): Boolean = handoffComplete && !confirmedUserSpeech

    /** Always false — NO_MATCH / SPEECH_TIMEOUT must not restart SpeechRecognizer. */
    fun shouldRearmAfterNoSpeechError(
        handoffComplete: Boolean,
        confirmedUserSpeech: Boolean,
        rearmCount: Int,
        nowMs: Long = 0L,
        absoluteDeadlineMs: Long = 0L,
        maxRearms: Int = MAX_PRE_SPEECH_REARMS,
    ): Boolean {
        // Keep signature for tests; runtime listener never re-arms.
        return false && handoffComplete && !confirmedUserSpeech &&
            rearmCount < maxRearms &&
            (absoluteDeadlineMs <= 0L || nowMs < absoluteDeadlineMs)
    }

    fun remainingNoSpeechMs(nowMs: Long, absoluteDeadlineMs: Long): Long {
        if (absoluteDeadlineMs <= 0L) return 0L
        return (absoluteDeadlineMs - nowMs).coerceAtLeast(0L)
    }
}
