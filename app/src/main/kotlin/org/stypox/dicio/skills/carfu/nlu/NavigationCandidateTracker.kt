package org.stypox.dicio.skills.carfu.nlu

import org.stypox.dicio.io.session.CanonicalCommand
import org.stypox.dicio.io.session.CarfuDiag
import org.stypox.dicio.io.session.CarfuLog
import org.stypox.dicio.io.session.UnderstandingResult
import org.stypox.dicio.io.session.VietnameseTranscript
import org.stypox.dicio.io.session.VoiceLifecycleLog
import org.stypox.dicio.io.session.VoiceIntent

/**
 * Per-VoiceSession NAV destination tracker. Prefers the latest longer stable candidate
 * in the same utterance and does not immediately downgrade on STT shrink.
 *
 * State is dropped on [bind] / [reset] / [markCancelled] — never persists across sessions.
 *
 * Timer reevaluation of an unchanged candidate must not rewrite [candidateChangedAt].
 */
internal enum class NavigationCandidateRelation {
    NEW,
    EXTENDED,
    CORRECTED,
    SHRUNK,
    UNCHANGED,
}

internal enum class NavTimerSource {
    PARTIAL,
    STABILITY_TIMER,
}

internal data class NavigationCandidateSnapshot(
    val sessionId: Long,
    val rawPartial: String,
    val normalizedDestination: String,
    val preferredDestination: String,
    val tokenCount: Int,
    val timestampMs: Long,
    val relation: NavigationCandidateRelation,
    val candidateChanged: Boolean,
    val candidateChangedAt: Long,
    val completeness: NavigationCommitPolicy.Completeness,
    val stableForMs: Long,
    val timerSource: NavTimerSource,
    val preferred: UnderstandingResult?,
)

internal object NavigationCandidateTracker {
    private val lock = Any()
    private var sessionId: Long = 0L
    private var cancelled: Boolean = false
    private var preferredComplete: UnderstandingResult? = null
    private var lastRawPartial: String = ""
    private var lastIncomingDestFolded: String = ""
    private var lastRelation: NavigationCandidateRelation = NavigationCandidateRelation.NEW
    /** Clock of the last *meaningful* destination change. Timer must not write this. */
    private var candidateChangedAt: Long = -1L
    private var blockedByIncompleteGrowth: Boolean = false

    fun bind(newSessionId: Long) {
        synchronized(lock) {
            sessionId = newSessionId
            cancelled = false
            preferredComplete = null
            lastRawPartial = ""
            lastIncomingDestFolded = ""
            lastRelation = NavigationCandidateRelation.NEW
            candidateChangedAt = -1L
            blockedByIncompleteGrowth = false
        }
    }

    fun markCancelled() {
        synchronized(lock) {
            cancelled = true
            preferredComplete = null
            lastRawPartial = ""
            lastIncomingDestFolded = ""
            lastRelation = NavigationCandidateRelation.NEW
            candidateChangedAt = -1L
            blockedByIncompleteGrowth = false
        }
    }

    fun reset() {
        bind(0L)
    }

    fun preferredResult(): UnderstandingResult? = synchronized(lock) { preferredComplete }

    fun lastRelation(): NavigationCandidateRelation = synchronized(lock) { lastRelation }

    fun isBlockedByIncompleteGrowth(): Boolean = synchronized(lock) { blockedByIncompleteGrowth }

    fun candidateChangedAt(): Long = synchronized(lock) { candidateChangedAt }

    fun stableForMs(nowMs: Long): Long = synchronized(lock) {
        if (candidateChangedAt < 0L) 0L else (nowMs - candidateChangedAt).coerceAtLeast(0L)
    }

    /**
     * Read-only timer reevaluation. Must not classify EXTENDED or move [candidateChangedAt].
     */
    fun peekForTimer(nowMs: Long): NavigationCandidateSnapshot? = synchronized(lock) {
        if (cancelled || sessionId == 0L) return@synchronized null
        snapshotLocked(
            nowMs = nowMs,
            relation = lastRelation,
            candidateChanged = false,
            timerSource = NavTimerSource.STABILITY_TIMER,
            incomingDest = preferredComplete?.let { destinationOf(it) }.orEmpty(),
        ).also { logCandidate(it, commitReason = "timer_reeval") }
    }

    fun observe(
        forSessionId: Long,
        nowMs: Long,
        incoming: UnderstandingResult,
        timerSource: NavTimerSource = NavTimerSource.PARTIAL,
    ): NavigationCandidateSnapshot? = synchronized(lock) {
        if (cancelled || forSessionId == 0L || forSessionId != sessionId) return@synchronized null
        if (timerSource == NavTimerSource.STABILITY_TIMER) {
            return@synchronized snapshotLocked(
                nowMs = nowMs,
                relation = NavigationCandidateRelation.UNCHANGED,
                candidateChanged = false,
                timerSource = NavTimerSource.STABILITY_TIMER,
                incomingDest = destinationOf(incoming),
            ).also { logCandidate(it, commitReason = "timer_reeval") }
        }
        val incomingDest = destinationOf(incoming)
        val incomingFolded = VietnameseTranscript.foldForMatch(incomingDest)
        val preferredDest = preferredComplete?.let { destinationOf(it) }.orEmpty()
        val identicalIncoming = lastIncomingDestFolded.isNotEmpty() &&
            incomingFolded == lastIncomingDestFolded
        val relation = if (identicalIncoming) {
            NavigationCandidateRelation.UNCHANGED
        } else {
            classify(preferredDest, incomingDest)
        }
        val candidateChanged = !identicalIncoming &&
            (
                relation == NavigationCandidateRelation.NEW ||
                    relation == NavigationCandidateRelation.EXTENDED ||
                    relation == NavigationCandidateRelation.CORRECTED
                )
        lastRawPartial = incoming.rawTranscript
        lastIncomingDestFolded = incomingFolded
        lastRelation = relation
        when (relation) {
            NavigationCandidateRelation.SHRUNK -> {
                // Keep the longer high-confidence candidate; do not reset the stability clock.
                blockedByIncompleteGrowth = false
            }
            NavigationCandidateRelation.UNCHANGED -> {
                if (incomingDest.isNotBlank() && NavigationCommitPolicy.isCompleteNavigate(incoming)) {
                    preferredComplete = incoming
                    blockedByIncompleteGrowth = false
                }
            }
            NavigationCandidateRelation.NEW,
            NavigationCandidateRelation.EXTENDED,
            NavigationCandidateRelation.CORRECTED,
            -> {
                val incomingIncomplete = incomingDest.isBlank() ||
                    NavigationCommitPolicy.isIncompleteDestination(incomingDest)
                if (!incomingIncomplete) {
                    preferredComplete = incoming
                    if (candidateChanged) {
                        candidateChangedAt = nowMs
                    }
                    blockedByIncompleteGrowth = false
                } else {
                    // Meaningful growth that is still an incomplete head (e.g. "số 25" →
                    // "số 25 phố") must reset the clock and block commit of the shorter dest.
                    blockedByIncompleteGrowth = preferredComplete != null &&
                        relation != NavigationCandidateRelation.NEW
                    if (candidateChanged &&
                        (blockedByIncompleteGrowth || preferredComplete == null)
                    ) {
                        candidateChangedAt = nowMs
                    }
                }
            }
        }
        snapshotLocked(
            nowMs = nowMs,
            relation = relation,
            candidateChanged = candidateChanged,
            timerSource = NavTimerSource.PARTIAL,
            incomingDest = incomingDest,
        ).also { logCandidate(it, commitReason = "observe") }
    }

    fun logCommit(nowMs: Long, reason: String, destination: String) {
        val snapshot = synchronized(lock) {
            snapshotLocked(
                nowMs = nowMs,
                relation = lastRelation,
                candidateChanged = false,
                timerSource = NavTimerSource.STABILITY_TIMER,
                incomingDest = destination,
            )
        }
        logCandidate(snapshot, commitReason = reason)
    }

    internal fun classify(previousDest: String, incomingDest: String): NavigationCandidateRelation {
        val prev = VietnameseTranscript.foldForMatch(previousDest)
        val next = VietnameseTranscript.foldForMatch(incomingDest)
        if (prev.isEmpty() && next.isEmpty()) return NavigationCandidateRelation.UNCHANGED
        if (prev.isEmpty()) return NavigationCandidateRelation.NEW
        if (next.isEmpty() || next == prev) {
            return if (next == prev) {
                NavigationCandidateRelation.UNCHANGED
            } else {
                NavigationCandidateRelation.SHRUNK
            }
        }
        val prevTokens = prev.split(" ").filter { it.isNotEmpty() }
        val nextTokens = next.split(" ").filter { it.isNotEmpty() }
        if (nextTokens.size > prevTokens.size && nextTokens.take(prevTokens.size) == prevTokens) {
            return NavigationCandidateRelation.EXTENDED
        }
        if (next.startsWith("$prev ")) return NavigationCandidateRelation.EXTENDED
        if (prevTokens.size > nextTokens.size && prevTokens.take(nextTokens.size) == nextTokens) {
            return NavigationCandidateRelation.SHRUNK
        }
        if (prev.startsWith("$next ")) return NavigationCandidateRelation.SHRUNK
        return NavigationCandidateRelation.CORRECTED
    }

    private fun snapshotLocked(
        nowMs: Long,
        relation: NavigationCandidateRelation,
        candidateChanged: Boolean,
        timerSource: NavTimerSource,
        incomingDest: String,
    ): NavigationCandidateSnapshot {
        val preferred = preferredComplete
        val preferredDest = preferred?.let { destinationOf(it) }.orEmpty()
        val dest = preferredDest.ifBlank { incomingDest }
        val completeness = NavigationCommitPolicy.completenessOf(dest)
        val stableFor = if (candidateChangedAt < 0L) 0L else (nowMs - candidateChangedAt).coerceAtLeast(0L)
        return NavigationCandidateSnapshot(
            sessionId = sessionId,
            rawPartial = lastRawPartial,
            normalizedDestination = dest,
            preferredDestination = preferredDest,
            tokenCount = dest.split(" ").filter { it.isNotEmpty() }.size,
            timestampMs = nowMs,
            relation = relation,
            candidateChanged = candidateChanged,
            candidateChangedAt = candidateChangedAt,
            completeness = completeness,
            stableForMs = stableFor,
            timerSource = timerSource,
            preferred = preferred,
        )
    }

    private fun destinationOf(result: UnderstandingResult): String {
        return (result.command as? CanonicalCommand.Navigate)?.destination?.trim().orEmpty()
            .ifBlank { result.destination.orEmpty().trim() }
    }

    private fun logCandidate(snapshot: NavigationCandidateSnapshot, commitReason: String) {
        val line =
            "NAV_CANDIDATE SESSION_ID=${snapshot.sessionId} " +
                "SR_RAW_PARTIAL=${snapshot.rawPartial} " +
                "NORMALIZED_DESTINATION=${snapshot.normalizedDestination} " +
                "PREFERRED_DESTINATION=${snapshot.preferredDestination} " +
                "CANDIDATE_RELATION=${snapshot.relation} " +
                "CANDIDATE_CHANGED=${snapshot.candidateChanged} " +
                "CANDIDATE_CHANGED_AT=${snapshot.candidateChangedAt} " +
                "NOW_MS=${snapshot.timestampMs} " +
                "NAV_STABLE_FOR_MS=${snapshot.stableForMs} " +
                "NAV_COMPLETENESS=${snapshot.completeness} " +
                "TIMER_SOURCE=${snapshot.timerSource} " +
                "NAV_COMMIT_REASON=$commitReason"
        CarfuLog.i(VoiceLifecycleLog.TAG, line)
        CarfuDiag.voice(line)
    }

    fun isNavigateRelated(result: UnderstandingResult): Boolean {
        if (result.intent == VoiceIntent.NAVIGATE) return true
        if (result.command is CanonicalCommand.Navigate) return true
        return result.reason.startsWith("nav_")
    }
}
