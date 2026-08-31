package org.stypox.dicio.io.session

/**
 * Process-wide idempotent entry gate for command sessions.
 *
 * VoiceInteractionService, ACTION_ASSIST, ACTION_VOICE_COMMAND, ACTION_VOICE_ASSIST,
 * WEB_SEARCH and the wake-word detector must all request a start here. One physical
 * MODE press therefore creates at most one [CommandSession].
 *
 * Origins match the production audit vocabulary:
 * WAKE_WORD / HARDWARE_BUTTON / UI / SERVICE_RESTART.
 */
object CarfuSessionGate {
    enum class Origin {
        WAKE_WORD,
        HARDWARE_BUTTON,
        UI,
        SERVICE_RESTART,
    }

    enum class Decision {
        ACCEPTED,
        REJECTED_BUSY,
        REJECTED_DEBOUNCE,
        REJECTED_WAKE_OFF,
        REJECTED_MODEL_NOT_READY,
        REJECTED_EMPTY_COOLDOWN,
        REJECTED_SERVICE_RESTART,
    }

    data class RequestResult(
        val decision: Decision,
        val sessionId: Long,
        val origin: Origin,
        val phase: CommandSessionPhase,
        val intentAction: String?,
        val intentComponent: String?,
        val accepted: Boolean,
        val timestampMs: Long,
    )

    const val ASSIST_DEBOUNCE_MS = 2_500L
    const val EMPTY_RESTART_COOLDOWN_MS = 10_000L
    const val TAG = "CarfuAssist"

    var nowMs: () -> Long = { System.currentTimeMillis() }

    @Volatile
    var backgroundWakeEnabled: Boolean = false
        private set

    @Volatile
    var activeSessionId: Long = 0L
        private set

    @Volatile
    var activeOrigin: Origin? = null
        private set

    @Volatile
    var lastAcceptedSessionId: Long = 0L
        private set

    @Volatile
    var pendingIntentAction: String? = null

    @Volatile
    var pendingIntentComponent: String? = null

    private var lastHardwareAcceptMs: Long = 0L
    private var emptyCooldownUntilMs: Long = 0L
    private val cancelledIds = HashSet<Long>()
    private val lock = Any()

    fun noteIncomingIntent(action: String?, component: String?) {
        pendingIntentAction = action
        pendingIntentComponent = component
    }

    fun setBackgroundWakeEnabled(enabled: Boolean) {
        synchronized(lock) {
            backgroundWakeEnabled = enabled
        }
        CarfuLog.i(TAG, "BACKGROUND_WAKE enabled=$enabled")
    }

    fun isCurrent(sessionId: Long): Boolean {
        synchronized(lock) {
            return sessionId != 0L &&
                sessionId == activeSessionId &&
                sessionId !in cancelledIds
        }
    }

    fun fromActivation(kind: CarfuActivationSource.Kind): Origin = when (kind) {
        CarfuActivationSource.Kind.AUTOMATIC_WAKE -> Origin.WAKE_WORD
        CarfuActivationSource.Kind.HARDWARE_BUTTON -> Origin.HARDWARE_BUTTON
        CarfuActivationSource.Kind.MANUAL_MIC -> Origin.UI
    }

    /**
     * @param startSession must begin the [CommandSession] and return its sessionId,
     * or return 0 if the machine refused.
     */
    fun requestStart(
        origin: Origin,
        phase: CommandSessionPhase,
        intentAction: String? = pendingIntentAction,
        intentComponent: String? = pendingIntentComponent,
        modelReady: Boolean = true,
        startSession: () -> Long,
    ): RequestResult {
        val result = synchronized(lock) {
            val ts = nowMs()
            fun reject(decision: Decision, sessionId: Long = activeSessionId): RequestResult {
                return RequestResult(
                    decision = decision,
                    sessionId = sessionId,
                    origin = origin,
                    phase = phase,
                    intentAction = intentAction,
                    intentComponent = intentComponent,
                    accepted = false,
                    timestampMs = ts,
                )
            }

            if (origin == Origin.SERVICE_RESTART) {
                return@synchronized reject(Decision.REJECTED_SERVICE_RESTART, 0L)
            }
            if (origin == Origin.WAKE_WORD && !backgroundWakeEnabled) {
                return@synchronized reject(Decision.REJECTED_WAKE_OFF)
            }
            if (activeSessionId != 0L) {
                return@synchronized reject(Decision.REJECTED_BUSY)
            }
            if (origin == Origin.HARDWARE_BUTTON &&
                lastHardwareAcceptMs > 0L &&
                ts - lastHardwareAcceptMs < ASSIST_DEBOUNCE_MS
            ) {
                return@synchronized reject(Decision.REJECTED_DEBOUNCE)
            }
            if (ts < emptyCooldownUntilMs) {
                return@synchronized reject(Decision.REJECTED_EMPTY_COOLDOWN)
            }
            if (!modelReady && origin == Origin.WAKE_WORD) {
                return@synchronized reject(Decision.REJECTED_MODEL_NOT_READY)
            }

            val id = startSession()
            if (id == 0L) {
                return@synchronized reject(Decision.REJECTED_BUSY)
            }
            activeSessionId = id
            activeOrigin = origin
            lastAcceptedSessionId = id
            cancelledIds.remove(id)
            if (origin == Origin.HARDWARE_BUTTON) {
                lastHardwareAcceptMs = ts
            }
            RequestResult(
                decision = Decision.ACCEPTED,
                sessionId = id,
                origin = origin,
                phase = phase,
                intentAction = intentAction,
                intentComponent = intentComponent,
                accepted = true,
                timestampMs = ts,
            )
        }
        log(result)
        return result
    }

    fun logServiceRestart(intentAction: String?, phase: CommandSessionPhase) {
        requestStart(
            origin = Origin.SERVICE_RESTART,
            phase = phase,
            intentAction = intentAction,
            intentComponent = null,
            modelReady = true,
            startSession = { 0L },
        )
    }

    /**
     * Cancel a live session. By default only WAKE_WORD sessions are cancelled so a
     * manual MODE press can finish after background wake is switched off.
     */
    fun cancel(reason: String, onlyOrigin: Origin? = Origin.WAKE_WORD): Long {
        val sid: Long
        val origin: Origin?
        synchronized(lock) {
            origin = activeOrigin
            if (activeSessionId == 0L) {
                sid = 0L
            } else if (onlyOrigin != null && origin != onlyOrigin) {
                sid = 0L
            } else {
                sid = activeSessionId
                cancelledIds.add(sid)
                activeSessionId = 0L
                activeOrigin = null
            }
        }
        if (sid != 0L) {
            CarfuLog.i(
                TAG,
                "SESSION_CANCELLED sessionId=$sid origin=$origin reason=$reason",
            )
        } else if (origin != null && onlyOrigin != null && origin != onlyOrigin) {
            CarfuLog.i(
                TAG,
                "SESSION_CANCEL_SKIPPED origin=$origin reason=$reason",
            )
        }
        return sid
    }

    fun onSessionFinished(
        sessionId: Long,
        hadTranscript: Boolean,
        origin: Origin,
    ) {
        val ts = nowMs()
        synchronized(lock) {
            if (sessionId != 0L && sessionId == activeSessionId) {
                activeSessionId = 0L
                activeOrigin = null
            }
            if (sessionId != 0L) {
                cancelledIds.add(sessionId)
            }
            if (!hadTranscript) {
                emptyCooldownUntilMs = ts + EMPTY_RESTART_COOLDOWN_MS
            }
        }
        CarfuLog.i(
            TAG,
            "SESSION_FINISHED sessionId=$sessionId origin=$origin " +
                "hadTranscript=$hadTranscript emptyCooldown=${!hadTranscript} timestamp=$ts",
        )
    }

    fun returningToWakeMayRestartListening(): Boolean = backgroundWakeEnabled

    private fun log(result: RequestResult) {
        CarfuLog.i(
            TAG,
            "SESSION_START_REQUEST sessionId=${result.sessionId} origin=${result.origin} " +
                "intentAction=${result.intentAction ?: "(none)"} " +
                "component=${result.intentComponent ?: "(none)"} phase=${result.phase} " +
                "accepted=${result.accepted} decision=${result.decision} " +
                "timestamp=${result.timestampMs}",
        )
    }

    fun resetForTests() {
        synchronized(lock) {
            nowMs = { System.currentTimeMillis() }
            backgroundWakeEnabled = true
            activeSessionId = 0L
            activeOrigin = null
            lastAcceptedSessionId = 0L
            pendingIntentAction = null
            pendingIntentComponent = null
            lastHardwareAcceptMs = 0L
            emptyCooldownUntilMs = 0L
            cancelledIds.clear()
        }
    }
}
