package org.stypox.dicio.io.session

/**
 * Session-owned async work must not mutate a different VoiceSession.
 *
 * SpeechRecognizer generation already drops wrong-listener callbacks; this guard
 * covers Handler timers, queued InputEvents, and TTS completion runnables.
 */
object VoiceSessionGuard {
    @Volatile
    private var navTimerSessionId: Long = 0L

    @Volatile
    private var lastStaleDropped: Boolean = false

    fun isActive(sessionId: Long): Boolean =
        sessionId != 0L && VoiceSessionManager.isLive(sessionId)

    /**
     * @return true when the callback must be ignored.
     */
    fun dropIfStale(callbackSessionId: Long, source: String): Boolean {
        val activeId = VoiceSessionManager.liveSession()?.sessionId ?: 0L
        val stale = !isActive(callbackSessionId)
        return logDrop(stale, callbackSessionId, activeId, source)
    }

    /**
     * For post-terminal resume/cleanup: drop only when a *different* session is live.
     * The ended session is never [isActive], so [dropIfStale] would always fire.
     */
    fun dropIfForeignLiveSession(endedSessionId: Long, source: String): Boolean {
        val live = VoiceSessionManager.liveSession()
        val activeId = live?.sessionId ?: 0L
        val stale = live != null && live.sessionId != endedSessionId
        return logDrop(stale, endedSessionId, activeId, source)
    }

    private fun logDrop(
        stale: Boolean,
        callbackSessionId: Long,
        activeId: Long,
        source: String,
    ): Boolean {
        lastStaleDropped = stale
        val command = SessionCommandDecision.locked(callbackSessionId)?.intent
            ?: SessionCommandDecision.locked(activeId)?.intent
        val line =
            "SESSION_ID=$activeId STATE=${VoiceSessionManager.state()} EVENT_SOURCE=$source " +
                "CALLBACK_SESSION_ID=$callbackSessionId ACTIVE_SESSION_ID=$activeId " +
                "STALE_CALLBACK_DROPPED=$stale COMMAND=${command ?: ""} " +
                "ACTION_CLAIMED=${CanonicalActionGate.claimedForTests()} " +
                "PENDING_NAV_TIMER_COUNT=${pendingNavTimerCount()}"
        CarfuLog.i(VoiceLifecycleLog.TAG, line)
        CarfuDiag.voice(line)
        return stale
    }

    fun armNavTimer(sessionId: Long) {
        navTimerSessionId = if (sessionId == 0L) 0L else sessionId
    }

    fun cancelNavTimer() {
        navTimerSessionId = 0L
    }

    fun pendingNavTimerCount(): Int = if (navTimerSessionId != 0L) 1 else 0

    fun lastStaleDroppedForTests(): Boolean = lastStaleDropped

    fun navTimerSessionIdForTests(): Long = navTimerSessionId

    fun resetForTests() {
        navTimerSessionId = 0L
        lastStaleDropped = false
    }
}
