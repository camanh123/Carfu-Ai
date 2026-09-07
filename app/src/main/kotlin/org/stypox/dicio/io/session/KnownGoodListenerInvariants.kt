package org.stypox.dicio.io.session

import org.stypox.dicio.io.input.CommandRecognitionPolicy

/**
 * JVM-testable one-MODE listener invariants for the restored known-good spine.
 * Does not own the recognizer; documents expected counts and terminal rules.
 */
object KnownGoodListenerInvariants {
    const val START_LISTENING_PER_MODE = 1
    const val MAX_ACTIVE_RECOGNIZERS = 1
    /** V2: MODE has no spoken ACK (“Tôi nghe đây”). */
    const val MAX_ACK_PER_MODE = 0
    const val MAX_COMMAND_SESSION_PER_MODE = 1
    const val MAX_EXECUTIONS_PER_MODE = 1
    const val MAX_FAILURE_TTS_PER_MODE = 0
    const val HARD_LISTEN_CEILING_MS = CommandRecognitionPolicy.ANDROID_LISTEN_TIMEOUT_MS
    const val PRODUCT_NO_SPEECH_TIMEOUT_MS = VoiceSessionManager.NO_SPEECH_TIMEOUT_MS

    fun modeUsesSpokenAck(): Boolean = VoiceSessionManager.modeUsesSpokenAck()

    fun noSpeechExitsSilently(): Boolean = !VoiceSessionManager.shouldSpeakNoSpeechPrompt()

    fun startListeningCountForModePress(rearmAttempts: Int = 0): Int {
        // Re-arm is disabled; extra attempts must not increase starts.
        if (CommandRecognitionPolicy.shouldRearmSpeechRecognizer(rearmAttempts)) {
            return START_LISTENING_PER_MODE + rearmAttempts
        }
        return START_LISTENING_PER_MODE
    }

    fun bosOrEosMayRearm(): Boolean = false

    fun noMatchMayRetry(): Boolean =
        CommandListenHandoffPolicy.shouldRearmAfterNoSpeechError(
            handoffComplete = true,
            confirmedUserSpeech = false,
            rearmCount = 0,
        )

    fun speechTimeoutMayRetry(): Boolean =
        CommandRecognitionPolicy.shouldRearmSpeechRecognizer(0)

    fun partialMayTerminateListener(): Boolean = false

    fun fastPartialRuntimeExecutionEnabled(): Boolean = false

    fun appSideNoSpeechKillerMs(): Long =
        CommandRecognitionPolicy.ANDROID_NO_SPEECH_AFTER_READY_MS

    fun usesGoogleSilenceIntentExtras(): Boolean = false

    fun backgroundWakeOffMayStartCommandListener(
        backgroundServiceAlive: Boolean,
        wakeWordEnabled: Boolean,
        freshModeOrUiAssist: Boolean,
    ): Boolean {
        if (freshModeOrUiAssist) return true
        if (!wakeWordEnabled) return false
        return backgroundServiceAlive && wakeWordEnabled
    }

    fun automaticRestartAfterSessionEndMs(elapsedSinceEndMs: Long): Boolean {
        // No automatic command-session restart after idle time.
        return false && elapsedSinceEndMs > 15_000L
    }
}
