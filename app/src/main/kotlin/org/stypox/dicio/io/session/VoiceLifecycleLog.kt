package org.stypox.dicio.io.session

/**
 * Compact monotonic V2 voice-spine lifecycle logs.
 * Never logs raw audio. Includes triggerId / sessionId / origin where applicable.
 */
object VoiceLifecycleLog {
    const val TAG = "CarfuVoiceV2"

    fun triggerRequest(origin: VoiceTriggerManager.Origin, reason: String) {
        CarfuLog.i(TAG, "TRIGGER_REQUEST origin=$origin reason=$reason")
    }

    fun triggerAccepted(trigger: VoiceTriggerManager.Trigger) {
        CarfuLog.i(
            TAG,
            "TRIGGER_ACCEPTED triggerId=${trigger.triggerId} origin=${trigger.origin} " +
                "ts=${trigger.timestampMs}",
        )
    }

    fun triggerRejected(
        origin: VoiceTriggerManager.Origin,
        decision: VoiceTriggerManager.Decision,
        reason: String,
    ) {
        CarfuLog.i(
            TAG,
            "TRIGGER_REJECTED origin=$origin decision=$decision reason=$reason",
        )
    }

    fun sessionCreated(session: VoiceSessionManager.Session) {
        CarfuLog.i(
            TAG,
            "SESSION_CREATED sessionId=${session.sessionId} triggerId=${session.triggerId} " +
                "origin=${session.origin}",
        )
    }

    fun sessionState(
        session: VoiceSessionManager.Session,
        state: VoiceSessionManager.State,
        reason: String = "",
    ) {
        val suffix = if (reason.isEmpty()) "" else " reason=$reason"
        CarfuLog.i(
            TAG,
            "SESSION_STATE sessionId=${session.sessionId} triggerId=${session.triggerId} " +
                "origin=${session.origin} state=$state$suffix",
        )
    }

    fun ackRequest(session: VoiceSessionManager.Session) {
        event("ACK_REQUEST", session)
    }

    fun ackStart(session: VoiceSessionManager.Session) {
        event("ACK_START", session)
    }

    fun ackDone(session: VoiceSessionManager.Session) {
        event("ACK_DONE", session)
    }

    fun listenStart(session: VoiceSessionManager.Session) {
        event("LISTEN_START", session)
    }

    fun listenTerminal(session: VoiceSessionManager.Session, reason: String) {
        event("LISTEN_TERMINAL", session, "reason=$reason")
    }

    fun liveTranscript(session: VoiceSessionManager.Session, text: String) {
        event("LIVE_TRANSCRIPT", session, "len=${text.length}")
    }

    fun understandingStart(session: VoiceSessionManager.Session) {
        event("UNDERSTANDING_START", session)
    }

    fun understandingResult(session: VoiceSessionManager.Session, summary: String) {
        event("UNDERSTANDING_RESULT", session, summary)
    }

    fun executionStart(session: VoiceSessionManager.Session) {
        event("EXECUTION_START", session)
    }

    fun executionDone(session: VoiceSessionManager.Session) {
        event("EXECUTION_DONE", session)
    }

    fun sessionTerminal(session: VoiceSessionManager.Session, reason: String) {
        event("SESSION_TERMINAL", session, "reason=$reason")
    }

    fun sessionIdle(session: VoiceSessionManager.Session) {
        event("SESSION_IDLE", session)
    }

    fun invariant(
        name: String,
        sessionId: Long?,
        triggerId: Long?,
        origin: VoiceTriggerManager.Origin?,
    ) {
        CarfuLog.w(
            TAG,
            "INVARIANT_$name sessionId=$sessionId triggerId=$triggerId origin=$origin",
        )
    }

    private fun event(
        name: String,
        session: VoiceSessionManager.Session,
        details: String = "",
    ) {
        val suffix = if (details.isEmpty()) "" else " $details"
        CarfuLog.i(
            TAG,
            "$name sessionId=${session.sessionId} triggerId=${session.triggerId} " +
                "origin=${session.origin}$suffix",
        )
    }
}
