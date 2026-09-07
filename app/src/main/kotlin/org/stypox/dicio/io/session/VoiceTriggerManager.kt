package org.stypox.dicio.io.session

/**
 * V2-CORE-1 authoritative entry for voice-session requests.
 *
 * Only deliberate user triggers may create a command session:
 * [Origin.HARDWARE_MODE] and [Origin.UI_MODE].
 *
 * Wake scores, service restarts, STT errors, timeouts, Activity recreation,
 * and other internal events are passive / rejected here — they must not
 * manufacture a conversation.
 */
object VoiceTriggerManager {
    enum class Origin {
        HARDWARE_MODE,
        UI_MODE,
        WAKE_WORD,
        SERVICE_RESTART,
        BACKGROUND_START,
        STT_ERROR,
        NO_MATCH,
        TIMEOUT,
        COOLDOWN_DONE,
        ACTIVITY_RECREATE,
        STALE_ASSIST,
        INTERNAL,
    }

    enum class Decision {
        ACCEPTED,
        CANCEL_CURRENT,
        REJECTED_UNAUTHORIZED,
        REJECTED_PASSIVE,
        REJECTED_DUPLICATE,
        REJECTED_BUSY,
        REJECTED_DEBOUNCE,
        REJECTED_STALE,
        REJECTED_CONSUMED,
    }

    data class Trigger(
        val triggerId: Long,
        val origin: Origin,
        val timestampMs: Long,
    )

    data class Result(
        val decision: Decision,
        val trigger: Trigger?,
        val accepted: Boolean,
        val reason: String = decision.name,
    )

    const val TAG = "CarfuVoiceV2"
    const val HARDWARE_DEBOUNCE_MS = CarfuSessionGate.ASSIST_DEBOUNCE_MS

    var nowMs: () -> Long = { System.currentTimeMillis() }

    @Volatile
    private var nextTriggerId: Long = 1L

    @Volatile
    private var lastHardwareAcceptMs: Long = 0L

    @Volatile
    private var openTrigger: Trigger? = null

    private val consumedTriggerIds = HashSet<Long>()
    private val triggerToSession = HashMap<Long, Long>()
    private val sessionToTrigger = HashMap<Long, Long>()
    private val lock = Any()

    fun isAuthorized(origin: Origin): Boolean =
        origin == Origin.HARDWARE_MODE || origin == Origin.UI_MODE

    fun isPassive(origin: Origin): Boolean = !isAuthorized(origin)

    /**
     * Request a new user trigger. Authorized origins may be accepted once;
     * passive origins are logged and rejected without creating a session path.
     */
    fun request(
        origin: Origin,
        staleAssist: Boolean = false,
        reason: String = origin.name,
    ): Result {
        VoiceLifecycleLog.triggerRequest(origin, reason)
        val result = synchronized(lock) {
            val ts = nowMs()
            if (staleAssist || origin == Origin.STALE_ASSIST) {
                return@synchronized reject(Decision.REJECTED_STALE, origin, ts, "stale_assist")
            }
            if (isPassive(origin)) {
                return@synchronized reject(Decision.REJECTED_PASSIVE, origin, ts, reason)
            }
            if (!isAuthorized(origin)) {
                return@synchronized reject(Decision.REJECTED_UNAUTHORIZED, origin, ts, reason)
            }
            if (openTrigger != null || VoiceSessionManager.hasLiveSession()) {
                // LISTENING + MODE/UI → cancel (after fan-out window). Not a new session.
                if (isAuthorized(origin) && VoiceSessionManager.shouldToggleCancelOnMode()) {
                    lastHardwareAcceptMs = 0L
                    return@synchronized Result(
                        decision = Decision.CANCEL_CURRENT,
                        trigger = null,
                        accepted = false,
                        reason = "mode_toggle_cancel",
                    )
                }
                return@synchronized reject(Decision.REJECTED_BUSY, origin, ts, "session_busy")
            }
            // Manual MODE/UI must not use a long cooldown after cancel/silence.
            // Keep a short fan-out debounce only for HARDWARE_MODE Assist duplicates.
            if (origin == Origin.HARDWARE_MODE &&
                lastHardwareAcceptMs > 0L &&
                ts - lastHardwareAcceptMs < HARDWARE_DEBOUNCE_MS
            ) {
                return@synchronized reject(Decision.REJECTED_DEBOUNCE, origin, ts, "assist_debounce")
            }

            val id = nextTriggerId++
            val trigger = Trigger(triggerId = id, origin = origin, timestampMs = ts)
            openTrigger = trigger
            if (origin == Origin.HARDWARE_MODE) {
                lastHardwareAcceptMs = ts
            }
            Result(Decision.ACCEPTED, trigger, accepted = true)
        }
        if (result.accepted) {
            VoiceLifecycleLog.triggerAccepted(result.trigger!!)
        } else {
            VoiceLifecycleLog.triggerRejected(origin, result.decision, result.reason)
        }
        return result
    }

    /**
     * Bind an accepted trigger to exactly one session. Returns false if the
     * trigger is missing, already consumed, or already bound.
     */
    fun bindSession(triggerId: Long, sessionId: Long): Boolean {
        synchronized(lock) {
            val open = openTrigger
            if (open == null || open.triggerId != triggerId) return false
            if (triggerId in consumedTriggerIds) return false
            if (triggerToSession.containsKey(triggerId)) return false
            if (sessionId == 0L) return false
            triggerToSession[triggerId] = sessionId
            sessionToTrigger[sessionId] = triggerId
            consumedTriggerIds.add(triggerId)
            openTrigger = null
            return true
        }
    }

    fun sessionForTrigger(triggerId: Long): Long? = synchronized(lock) {
        triggerToSession[triggerId]
    }

    fun triggerForSession(sessionId: Long): Long? = synchronized(lock) {
        sessionToTrigger[sessionId]
    }

    fun requireTriggerId(sessionId: Long): Long {
        val id = triggerForSession(sessionId)
        check(id != null && id != 0L) {
            "invariant: session=$sessionId has no legitimate triggerId"
        }
        return id
    }

    fun hasOpenTrigger(): Boolean = synchronized(lock) { openTrigger != null }

    fun peekOpenTrigger(): Trigger? = synchronized(lock) { openTrigger }

    /** Clear an accepted-but-unbound trigger (begin session failed). */
    fun abandonOpenTrigger(triggerId: Long, reason: String) {
        synchronized(lock) {
            val open = openTrigger
            if (open != null && open.triggerId == triggerId) {
                openTrigger = null
                consumedTriggerIds.add(triggerId)
            }
        }
        VoiceLifecycleLog.triggerRejected(
            Origin.INTERNAL,
            Decision.REJECTED_CONSUMED,
            "abandon_$reason triggerId=$triggerId",
        )
    }

    fun onSessionTerminal(sessionId: Long) {
        synchronized(lock) {
            val triggerId = sessionToTrigger.remove(sessionId)
            if (triggerId != null) {
                triggerToSession.remove(triggerId)
            }
            // Allow immediate next MODE / UI press after terminal (no stuck cooldown).
            lastHardwareAcceptMs = 0L
        }
    }

    /** Clear Assist debounce so the next deliberate MODE can start immediately. */
    fun clearHardwareDebounceForTests() {
        synchronized(lock) { lastHardwareAcceptMs = 0L }
    }

    fun clearHardwareDebounce() {
        synchronized(lock) { lastHardwareAcceptMs = 0L }
    }

    fun fromGateOrigin(origin: CarfuSessionGate.Origin): Origin = when (origin) {
        CarfuSessionGate.Origin.HARDWARE_BUTTON -> Origin.HARDWARE_MODE
        CarfuSessionGate.Origin.UI -> Origin.UI_MODE
        CarfuSessionGate.Origin.WAKE_WORD -> Origin.WAKE_WORD
        CarfuSessionGate.Origin.SERVICE_RESTART -> Origin.SERVICE_RESTART
    }

    fun toGateOrigin(origin: Origin): CarfuSessionGate.Origin? = when (origin) {
        Origin.HARDWARE_MODE -> CarfuSessionGate.Origin.HARDWARE_BUTTON
        Origin.UI_MODE -> CarfuSessionGate.Origin.UI
        Origin.WAKE_WORD -> CarfuSessionGate.Origin.WAKE_WORD
        Origin.SERVICE_RESTART -> CarfuSessionGate.Origin.SERVICE_RESTART
        else -> null
    }

    fun toActivationKind(origin: Origin): CarfuActivationSource.Kind? = when (origin) {
        Origin.HARDWARE_MODE -> CarfuActivationSource.Kind.HARDWARE_BUTTON
        Origin.UI_MODE -> CarfuActivationSource.Kind.MANUAL_MIC
        Origin.WAKE_WORD -> CarfuActivationSource.Kind.AUTOMATIC_WAKE
        else -> null
    }

    private fun reject(
        decision: Decision,
        origin: Origin,
        ts: Long,
        reason: String,
    ): Result = Result(
        decision = decision,
        trigger = null,
        accepted = false,
        reason = reason,
    )

    fun resetForTests() {
        synchronized(lock) {
            nowMs = { System.currentTimeMillis() }
            nextTriggerId = 1L
            lastHardwareAcceptMs = 0L
            openTrigger = null
            consumedTriggerIds.clear()
            triggerToSession.clear()
            sessionToTrigger.clear()
        }
    }
}
