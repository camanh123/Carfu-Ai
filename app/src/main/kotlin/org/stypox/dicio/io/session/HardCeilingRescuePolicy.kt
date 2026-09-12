package org.stypox.dicio.io.session

/**
 * Hard-ceiling rescue must not leave an UNKNOWN / non-executable session alive.
 *
 * Device-proven: SR_HARD_CEILING rescued "đưa" / "đưa tôi" as UNKNOWN, then
 * [SessionCommandDecision.decideFinal] locked that non-executable result. A
 * second rescue saw `already_locked` and returned success, skipping terminal.
 * The session hung until MODE cancel (~59s).
 *
 * Executable complete canonical commands still execute. Anything else terminates
 * exactly once — no retry loop, no new command session.
 */
internal object HardCeilingRescuePolicy {
    fun isExecutableCanonical(decision: UnderstandingResult?): Boolean {
        if (decision == null) return false
        if (decision.intent == VoiceIntent.UNKNOWN) return false
        if (decision.command == null) return false
        if (decision.completeness != SemanticCompleteness.COMPLETE) return false
        if (!decision.executable) return false
        if (decision.confidence < 0.85f && decision.recognizerConfidence < 0.85f) return false
        return true
    }

    /** Locked-but-not-executable must not be treated as a successful rescue. */
    fun shouldTreatLockedAsSuccessfulRescue(locked: UnderstandingResult?): Boolean =
        isExecutableCanonical(locked)

    fun shouldTerminateAfterRescue(decision: UnderstandingResult?): Boolean =
        !isExecutableCanonical(decision)

    fun log(
        sessionId: Long,
        rescue: UnderstandingResult?,
        terminalTriggered: Boolean,
        terminalReason: String,
        srDestroyed: Boolean,
        audioFocusReleased: Boolean,
    ) {
        val commandPresent = rescue?.command != null &&
            rescue.intent != VoiceIntent.UNKNOWN
        val rescueLabel = when {
            rescue == null -> "none"
            rescue.intent == VoiceIntent.UNKNOWN -> "UNKNOWN"
            rescue.completeness != SemanticCompleteness.COMPLETE ->
                rescue.completeness.name
            rescue.command == null -> "UNKNOWN"
            else -> rescue.intent.name
        }
        val line =
            "SR_HARD_CEILING SESSION_ID=$sessionId RESCUE_RESULT=$rescueLabel " +
                "CANONICAL_COMMAND_PRESENT=$commandPresent " +
                "TERMINAL_TRIGGERED=$terminalTriggered TERMINAL_REASON=$terminalReason " +
                "SR_DESTROYED=$srDestroyed AUDIO_FOCUS_RELEASED=$audioFocusReleased"
        CarfuLog.i(VoiceLifecycleLog.TAG, line)
        CarfuDiag.voice(line)
        CarfuLatencyLog.logSessionEvent("SR_HARD_CEILING", line)
    }
}
