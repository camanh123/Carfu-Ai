package org.stypox.dicio.io.session

/**
 * Exactly one terminal outcome per command session. Late SR timeout/no-speech callbacks
 * must not overwrite a completed EXECUTED or UNSUPPORTED result, and must not claim
 * a *different* VoiceSession's outcome slot.
 */
object CommandSessionOutcome {
    enum class Kind {
        OPEN,
        NO_SPEECH,
        UNSUPPORTED,
        EXECUTED,
        SR_ERROR,
    }

    private data class Slot(
        val sessionId: Long,
        val kind: Kind,
        val closed: Boolean = false,
    )

    private val lock = Any()
    private var slot: Slot = Slot(sessionId = 0L, kind = Kind.OPEN)

    fun reset() {
        synchronized(lock) {
            slot = Slot(sessionId = 0L, kind = Kind.OPEN)
        }
    }

    fun bind(sessionId: Long) {
        synchronized(lock) {
            slot = Slot(sessionId = sessionId, kind = Kind.OPEN)
        }
    }

    /** Terminal cleanup: later claims for [sessionId] are rejected. */
    fun close(sessionId: Long) {
        synchronized(lock) {
            val s = slot
            if (s.sessionId == sessionId || (sessionId != 0L && s.sessionId == 0L)) {
                slot = s.copy(closed = true)
            }
        }
    }

    fun peek(): Kind = synchronized(lock) { slot.kind }

    fun boundSessionIdForTests(): Long = synchronized(lock) { slot.sessionId }

    fun claimedForTests(): Boolean = synchronized(lock) {
        slot.kind != Kind.OPEN || slot.closed
    }

    /**
     * @param sessionId owning VoiceSession. `0` keeps legacy unscoped test callers working.
     */
    fun claim(kind: Kind, sessionId: Long = 0L): Boolean {
        synchronized(lock) {
            val s = slot
            if (s.closed) {
                CarfuLatencyLog.logSessionEvent(
                    "TERMINAL_IGNORED",
                    "closed session=${s.sessionId} requested=$kind from=$sessionId",
                )
                return false
            }
            if (sessionId != 0L && s.sessionId != 0L && sessionId != s.sessionId) {
                CarfuLatencyLog.logSessionEvent(
                    "STALE_CALLBACK_DROPPED",
                    "outcome_claim from=$sessionId bound=${s.sessionId} requested=$kind",
                )
                return false
            }
            if (s.kind != Kind.OPEN) {
                CarfuLatencyLog.logSessionEvent(
                    "TERMINAL_IGNORED",
                    "existing=${s.kind} requested=$kind",
                )
                return false
            }
            slot = s.copy(kind = kind)
            CarfuLatencyLog.logSessionEvent("TERMINAL", "outcome=$kind session=${s.sessionId}")
            return true
        }
    }

    fun resetForTests() {
        reset()
    }
}
