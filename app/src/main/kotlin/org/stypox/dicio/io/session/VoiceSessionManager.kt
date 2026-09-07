package org.stypox.dicio.io.session

/**
 * V2-CORE-1 authoritative owner of product voice-session state.
 *
 * SpeechRecognizer / WakeService / TTS callbacks are events INTO this manager.
 * They do not own the conversation lifecycle.
 *
 * MODE product path (Kiki-aligned):
 * IDLE → LISTENING → PROCESSING → EXECUTING/RESPONDING → TERMINAL → IDLE
 *
 * No spoken MODE ACK. No-speech ~[NO_SPEECH_TIMEOUT_MS] → silent terminal.
 */
object VoiceSessionManager {
    enum class State {
        IDLE,
        /** Retained for mapping legacy phases / action TTS windows — not used for MODE entry. */
        ACKNOWLEDGING,
        LISTENING,
        PROCESSING,
        EXECUTING,
        RESPONDING,
        TERMINAL,
    }

    data class Session(
        val sessionId: Long,
        val triggerId: Long,
        val origin: VoiceTriggerManager.Origin,
        val createdAtMs: Long,
    )

    data class Counters(
        val ackRequests: Int = 0,
        val ackStarts: Int = 0,
        val ackDones: Int = 0,
        val listenStarts: Int = 0,
        val listenTerminals: Int = 0,
        val understandingStarts: Int = 0,
        val executionStarts: Int = 0,
        val executionDones: Int = 0,
    )

    const val TAG = "CarfuVoiceV2"

    /** Product silence budget while listening with no speech. Owned here — not stacked SR timers. */
    const val NO_SPEECH_TIMEOUT_MS = 5_000L

    var nowMs: () -> Long = { System.currentTimeMillis() }

    @Volatile
    private var state: State = State.IDLE

    @Volatile
    private var live: Session? = null

    @Volatile
    private var counters: Counters = Counters()

    @Volatile
    private var lastTerminalAtMs: Long = 0L

    @Volatile
    private var listenArmedAtMs: Long = 0L

    @Volatile
    private var speechDetected: Boolean = false

    @Volatile
    private var rawLiveTranscript: String? = null

    private val lock = Any()

    /** MODE must not speak “Tôi nghe đây”. */
    fun modeUsesSpokenAck(): Boolean = false

    /** MODE/UI no-speech exits silently — no “Tôi chưa nghe rõ”. */
    fun shouldSpeakNoSpeechPrompt(): Boolean = false

    fun state(): State = state

    fun liveSession(): Session? = live

    fun hasLiveSession(): Boolean = synchronized(lock) {
        live != null && state != State.IDLE && state != State.TERMINAL
    }

    fun isLive(sessionId: Long): Boolean = synchronized(lock) {
        val s = live
        s != null && s.sessionId == sessionId && state != State.IDLE && state != State.TERMINAL
    }

    fun counters(): Counters = counters

    fun lastTerminalAtMs(): Long = lastTerminalAtMs

    fun rawLiveTranscript(): String? = rawLiveTranscript

    fun speechDetected(): Boolean = speechDetected

    /**
     * Second MODE/UI while actively listening cancels the session.
     * Ignores Assist fan-out that arrives in the first few hundred ms after start.
     */
    fun shouldToggleCancelOnMode(atMs: Long = nowMs()): Boolean {
        synchronized(lock) {
            val s = live ?: return false
            if (state != State.LISTENING) return false
            if (counters.listenStarts < 1) return false
            return atMs - s.createdAtMs >= TOGGLE_CANCEL_MIN_AGE_MS
        }
    }

    /** Assist VIS+ASSIST fan-out window; below this, busy means ignore not cancel. */
    const val TOGGLE_CANCEL_MIN_AGE_MS = 400L

    /**
     * Create a session from an accepted trigger. Caller supplies the underlying
     * [CommandSession] id after [CommandSession.tryBeginWakeSession].
     * Enters a pre-listen armed state; [requestListen] moves to LISTENING.
     */
    fun createFromTrigger(
        trigger: VoiceTriggerManager.Trigger,
        sessionId: Long,
    ): Session? {
        synchronized(lock) {
            if (sessionId == 0L) return null
            if (live != null && state != State.IDLE && state != State.TERMINAL) return null
            if (!VoiceTriggerManager.bindSession(trigger.triggerId, sessionId)) return null
            val session = Session(
                sessionId = sessionId,
                triggerId = trigger.triggerId,
                origin = trigger.origin,
                createdAtMs = nowMs(),
            )
            live = session
            counters = Counters()
            listenArmedAtMs = 0L
            speechDetected = false
            rawLiveTranscript = null
            // Past IDLE, awaiting the single logical listen (no MODE ACK).
            state = State.LISTENING
            VoiceLifecycleLog.sessionCreated(session)
            VoiceLifecycleLog.sessionState(session, state, "created_no_ack")
            return session
        }
    }

    /** MODE spoken ACK is disabled — always reject. Kept for invariant tests / legacy callers. */
    fun requestAck(sessionId: Long): Boolean {
        synchronized(lock) {
            if (!modeUsesSpokenAck()) {
                VoiceLifecycleLog.invariant(
                    "MODE_ACK_FORBIDDEN",
                    sessionId,
                    live?.triggerId,
                    live?.origin,
                )
                return false
            }
            if (!isLiveLocked(sessionId)) return false
            if (counters.ackRequests >= 1) return false
            counters = counters.copy(ackRequests = counters.ackRequests + 1)
            setStateLocked(State.ACKNOWLEDGING)
            VoiceLifecycleLog.ackRequest(live!!)
            return true
        }
    }

    fun onAckStart(sessionId: Long): Boolean {
        if (!modeUsesSpokenAck()) return false
        synchronized(lock) {
            if (!isLiveLocked(sessionId)) return false
            if (counters.ackStarts >= 1) return false
            counters = counters.copy(ackStarts = counters.ackStarts + 1)
            setStateLocked(State.ACKNOWLEDGING)
            VoiceLifecycleLog.ackStart(live!!)
            return true
        }
    }

    fun onAckDone(sessionId: Long): Boolean {
        if (!modeUsesSpokenAck()) return false
        synchronized(lock) {
            if (!isLiveLocked(sessionId)) return false
            if (counters.ackDones >= 1) return false
            counters = counters.copy(ackDones = counters.ackDones + 1)
            VoiceLifecycleLog.ackDone(live!!)
            return true
        }
    }

    fun requestListen(sessionId: Long): Boolean {
        synchronized(lock) {
            if (!isLiveLocked(sessionId)) return false
            if (counters.listenStarts >= 1) {
                VoiceLifecycleLog.invariant(
                    "LISTEN_DUPLICATE_REJECTED",
                    sessionId,
                    live?.triggerId,
                    live?.origin,
                )
                return false
            }
            counters = counters.copy(listenStarts = counters.listenStarts + 1)
            listenArmedAtMs = nowMs()
            speechDetected = false
            setStateLocked(State.LISTENING)
            VoiceLifecycleLog.listenStart(live!!)
            return true
        }
    }

    fun onLiveTranscript(sessionId: Long, text: String): Boolean {
        synchronized(lock) {
            if (!isLiveLocked(sessionId)) return false
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return false
            rawLiveTranscript = trimmed
            speechDetected = true
            VoiceLifecycleLog.liveTranscript(live!!, trimmed)
            return true
        }
    }

    fun onSpeechActivity(sessionId: Long): Boolean {
        synchronized(lock) {
            if (!isLiveLocked(sessionId)) return false
            speechDetected = true
            return true
        }
    }

    /**
     * Product silence rule: listening, no speech yet, elapsed ≥ [NO_SPEECH_TIMEOUT_MS].
     */
    fun shouldSilentExit(sessionId: Long, atMs: Long = nowMs()): Boolean {
        synchronized(lock) {
            val s = live
            if (s == null || s.sessionId != sessionId) return false
            if (state != State.LISTENING) return false
            if (speechDetected) return false
            if (listenArmedAtMs <= 0L) return false
            return atMs - listenArmedAtMs >= NO_SPEECH_TIMEOUT_MS
        }
    }

    fun onListenTerminal(sessionId: Long, reason: String): Boolean {
        synchronized(lock) {
            if (!isLiveLocked(sessionId) && live?.sessionId != sessionId) return false
            if (live?.sessionId != sessionId) return false
            if (counters.listenTerminals >= 1 && state != State.LISTENING) return false
            counters = counters.copy(listenTerminals = counters.listenTerminals + 1)
            VoiceLifecycleLog.listenTerminal(live!!, reason)
            return true
        }
    }

    fun onUnderstandingStart(sessionId: Long): Boolean {
        synchronized(lock) {
            if (!isLiveLocked(sessionId)) return false
            counters = counters.copy(understandingStarts = counters.understandingStarts + 1)
            setStateLocked(State.PROCESSING)
            VoiceLifecycleLog.understandingStart(live!!)
            return true
        }
    }

    fun onUnderstandingResult(sessionId: Long, summary: String): Boolean {
        synchronized(lock) {
            if (!isLiveLocked(sessionId)) return false
            VoiceLifecycleLog.understandingResult(live!!, summary)
            return true
        }
    }

    fun requestExecution(sessionId: Long): Boolean {
        synchronized(lock) {
            if (!isLiveLocked(sessionId)) return false
            if (counters.executionStarts >= 1) {
                VoiceLifecycleLog.invariant(
                    "EXECUTION_DUPLICATE_REJECTED",
                    sessionId,
                    live?.triggerId,
                    live?.origin,
                )
                return false
            }
            counters = counters.copy(executionStarts = counters.executionStarts + 1)
            setStateLocked(State.EXECUTING)
            VoiceLifecycleLog.executionStart(live!!)
            return true
        }
    }

    fun onExecutionDone(sessionId: Long): Boolean {
        synchronized(lock) {
            if (!isLiveLocked(sessionId)) return false
            counters = counters.copy(executionDones = counters.executionDones + 1)
            setStateLocked(State.RESPONDING)
            VoiceLifecycleLog.executionDone(live!!)
            return true
        }
    }

    fun onResponding(sessionId: Long): Boolean {
        synchronized(lock) {
            if (!isLiveLocked(sessionId)) return false
            setStateLocked(State.RESPONDING)
            return true
        }
    }

    /**
     * Mark session terminal then IDLE. Does not resurrect a new user session.
     */
    fun terminate(sessionId: Long, reason: String): Boolean {
        val session: Session
        synchronized(lock) {
            val s = live
            if (s == null || s.sessionId != sessionId) return false
            if (state == State.IDLE) return false
            session = s
            setStateLocked(State.TERMINAL)
            VoiceLifecycleLog.sessionTerminal(session, reason)
            lastTerminalAtMs = nowMs()
            live = null
            listenArmedAtMs = 0L
            speechDetected = false
            rawLiveTranscript = null
            setStateLocked(State.IDLE)
            VoiceLifecycleLog.sessionIdle(session)
        }
        SessionCommandDecision.clear(sessionId)
        CanonicalActionGate.clear(sessionId)
        VoiceTriggerManager.onSessionTerminal(sessionId)
        return true
    }

    /** Stale SpeechRecognizer / TTS callback after terminal must be ignored. */
    fun shouldIgnoreCallback(sessionId: Long): Boolean {
        synchronized(lock) {
            val s = live
            return s == null || s.sessionId != sessionId ||
                state == State.IDLE || state == State.TERMINAL
        }
    }

    fun mapFromCommandPhase(phase: CommandSessionPhase): State = when (phase) {
        CommandSessionPhase.IDLE_WAKE -> State.IDLE
        CommandSessionPhase.WAKE_DETECTED -> State.LISTENING
        CommandSessionPhase.ACKNOWLEDGING -> State.ACKNOWLEDGING
        CommandSessionPhase.COMMAND_LISTENING -> State.LISTENING
        CommandSessionPhase.PROCESSING -> State.PROCESSING
        CommandSessionPhase.RESPONDING -> State.RESPONDING
        CommandSessionPhase.RETURNING_TO_WAKE -> State.TERMINAL
    }

    private fun isLiveLocked(sessionId: Long): Boolean {
        val s = live
        return s != null && s.sessionId == sessionId &&
            state != State.IDLE && state != State.TERMINAL
    }

    private fun setStateLocked(next: State) {
        if (state != next) {
            state = next
            live?.let { VoiceLifecycleLog.sessionState(it, next) }
        } else {
            state = next
        }
    }

    fun resetForTests() {
        synchronized(lock) {
            nowMs = { System.currentTimeMillis() }
            state = State.IDLE
            live = null
            counters = Counters()
            lastTerminalAtMs = 0L
            listenArmedAtMs = 0L
            speechDetected = false
            rawLiveTranscript = null
        }
        SessionCommandDecision.resetForTests()
        CanonicalActionGate.resetForTests()
    }
}
