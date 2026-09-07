package org.stypox.dicio.io.session

/**
 * Exactly-once gate for Phase-3 CanonicalCommand side effects.
 *
 * Complements [CommandSessionOutcome] / [VoiceSessionManager.requestExecution]:
 * a cancelled or terminal session can never start an Android action, and a session
 * may claim at most one action.
 */
object CanonicalActionGate {
    private data class Slot(
        val sessionId: Long,
        val cancelled: Boolean = false,
        val claimed: Boolean = false,
        val completed: Boolean = false,
    )

    @Volatile
    private var slot: Slot? = null
    private val lock = Any()

    fun bind(sessionId: Long) {
        if (sessionId == 0L) return
        synchronized(lock) {
            slot = Slot(sessionId = sessionId)
        }
    }

    fun markCancelled(sessionId: Long) {
        synchronized(lock) {
            val s = slot ?: return
            if (s.sessionId != sessionId) return
            slot = s.copy(cancelled = true)
        }
    }

    fun clear(sessionId: Long) {
        synchronized(lock) {
            val s = slot ?: return
            if (s.sessionId == sessionId) slot = null
        }
    }

    /** Returns true once per session when an action may begin. */
    fun tryClaim(sessionId: Long): Boolean {
        synchronized(lock) {
            val s = slot
            if (s == null || s.sessionId != sessionId) return false
            if (s.cancelled || s.claimed || s.completed) return false
            slot = s.copy(claimed = true)
            return true
        }
    }

    fun markCompleted(sessionId: Long) {
        synchronized(lock) {
            val s = slot ?: return
            if (s.sessionId != sessionId) return
            slot = s.copy(completed = true)
        }
    }

    fun mayAct(sessionId: Long): Boolean = synchronized(lock) {
        val s = slot ?: return false
        s.sessionId == sessionId && !s.cancelled && !s.completed
    }

    fun isCancelled(sessionId: Long): Boolean = synchronized(lock) {
        val s = slot ?: return false
        s.sessionId == sessionId && s.cancelled
    }

    fun resetForTests() {
        synchronized(lock) { slot = null }
    }
}
