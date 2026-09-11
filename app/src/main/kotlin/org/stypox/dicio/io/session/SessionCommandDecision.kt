package org.stypox.dicio.io.session

/**
 * Session-bound ownership of provisional vs final command decisions.
 *
 * Partial/live transcripts may update a provisional understanding for UI/ranking.
 * They must not lock an executable [CanonicalCommand] until
 * [StableCompletePartialTracker] or a Final/[decideFinal] commit.
 *
 * Once a final decision is locked for a session, weaker/late candidates cannot
 * downgrade it. Cross-session candidates are rejected.
 */
object SessionCommandDecision {
    data class Slot(
        val sessionId: Long,
        val provisional: UnderstandingResult? = null,
        val locked: UnderstandingResult? = null,
        val cancelled: Boolean = false,
    )

    @Volatile
    private var slot: Slot? = null
    private val lock = Any()

    fun bindSession(sessionId: Long) {
        if (sessionId == 0L) return
        synchronized(lock) {
            slot = Slot(sessionId = sessionId)
        }
    }

    fun onPartial(sessionId: Long, result: UnderstandingResult) {
        synchronized(lock) {
            val s = slot ?: return
            if (s.cancelled || s.sessionId != sessionId) return
            if (s.locked != null) return
            // Provisional only — never executable lock from partial.
            slot = s.copy(provisional = result.copy(executable = false))
        }
    }

    /**
     * Rank [candidates] for [sessionId] and lock the best complete decision when eligible.
     * Returns null when the session is cancelled/stale or no decision can be made.
     */
    fun decideFinal(
        sessionId: Long,
        candidates: List<Pair<String, Float>>,
    ): UnderstandingResult? {
        synchronized(lock) {
            val s = slot
            if (s == null || s.sessionId != sessionId || s.cancelled) return null
            if (s.locked != null) return s.locked
        }
        val ranked = VietnameseCommandUnderstanding.rankCandidates(sessionId, candidates)
        val best = VietnameseCommandUnderstanding.selectDecision(ranked)
            ?: return UnderstandingResult.unknown(
                sessionId = sessionId,
                raw = candidates.firstOrNull()?.first.orEmpty(),
                normalized = "",
                reason = "no_candidates",
            )
        return lockFinal(sessionId, best)
    }

    fun lockFinal(sessionId: Long, result: UnderstandingResult): UnderstandingResult? {
        synchronized(lock) {
            val s = slot ?: return null
            if (s.cancelled || s.sessionId != sessionId) return null
            if (s.locked != null) {
                // Never downgrade a locked stronger decision with a weaker late callback.
                return s.locked
            }
            val locked = result.copy(sessionId = sessionId)
            slot = s.copy(locked = locked, provisional = locked)
            return locked
        }
    }

    fun tryAcceptLate(sessionId: Long, result: UnderstandingResult): UnderstandingResult? {
        synchronized(lock) {
            val s = slot ?: return null
            if (s.cancelled || s.sessionId != sessionId) return null
            val locked = s.locked ?: return lockFinalUnlocked(s, result)
            if (!VietnameseCommandUnderstanding.isStronger(result, locked)) {
                return locked
            }
            // Stronger complete late final may upgrade before execution claim.
            val upgraded = result.copy(sessionId = sessionId)
            slot = s.copy(locked = upgraded)
            return upgraded
        }
    }

    private fun lockFinalUnlocked(s: Slot, result: UnderstandingResult): UnderstandingResult {
        val locked = result.copy(sessionId = s.sessionId)
        slot = s.copy(locked = locked, provisional = locked)
        return locked
    }

    fun locked(sessionId: Long): UnderstandingResult? = synchronized(lock) {
        val s = slot ?: return null
        if (s.sessionId != sessionId || s.cancelled) return null
        s.locked
    }

    fun provisional(sessionId: Long): UnderstandingResult? = synchronized(lock) {
        val s = slot ?: return null
        if (s.sessionId != sessionId || s.cancelled) return null
        s.provisional
    }

    /** Cancelled session: later callbacks must not produce an executable command. */
    fun markCancelled(sessionId: Long) {
        synchronized(lock) {
            val s = slot ?: return
            if (s.sessionId != sessionId) return
            slot = s.copy(cancelled = true, locked = null, provisional = null)
        }
    }

    fun clear(sessionId: Long) {
        synchronized(lock) {
            val s = slot ?: return
            if (s.sessionId == sessionId) slot = null
        }
    }

    fun isCancelled(sessionId: Long): Boolean = synchronized(lock) {
        val s = slot ?: return false
        s.sessionId == sessionId && s.cancelled
    }

    fun resetForTests() {
        synchronized(lock) { slot = null }
    }
}
