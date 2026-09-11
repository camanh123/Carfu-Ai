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
 */
internal enum class NavigationCandidateRelation {
    NEW,
    EXTENDED,
    CORRECTED,
    SHRUNK,
    UNCHANGED,
}

internal data class NavigationCandidateSnapshot(
    val sessionId: Long,
    val rawPartial: String,
    val normalizedDestination: String,
    val tokenCount: Int,
    val timestampMs: Long,
    val relation: NavigationCandidateRelation,
    val completeness: NavigationCommitPolicy.Completeness,
    val stableForMs: Long,
    val preferred: UnderstandingResult?,
)

internal object NavigationCandidateTracker {
    private val lock = Any()
    private var sessionId: Long = 0L
    private var cancelled: Boolean = false
    private var preferredComplete: UnderstandingResult? = null
    private var lastRawPartial: String = ""
    private var lastRelation: NavigationCandidateRelation = NavigationCandidateRelation.NEW
    private var firstSeenMs: Long = -1L
    private var blockedByIncompleteGrowth: Boolean = false

    fun bind(newSessionId: Long) {
        synchronized(lock) {
            sessionId = newSessionId
            cancelled = false
            preferredComplete = null
            lastRawPartial = ""
            lastRelation = NavigationCandidateRelation.NEW
            firstSeenMs = -1L
            blockedByIncompleteGrowth = false
        }
    }

    fun markCancelled() {
        synchronized(lock) {
            cancelled = true
            preferredComplete = null
            lastRawPartial = ""
            lastRelation = NavigationCandidateRelation.NEW
            firstSeenMs = -1L
            blockedByIncompleteGrowth = false
        }
    }

    fun reset() {
        bind(0L)
    }

    fun preferredResult(): UnderstandingResult? = synchronized(lock) { preferredComplete }

    fun lastRelation(): NavigationCandidateRelation = synchronized(lock) { lastRelation }

    fun isBlockedByIncompleteGrowth(): Boolean = synchronized(lock) { blockedByIncompleteGrowth }

    fun stableForMs(nowMs: Long): Long = synchronized(lock) {
        if (firstSeenMs < 0L) 0L else (nowMs - firstSeenMs).coerceAtLeast(0L)
    }

    fun observe(
        forSessionId: Long,
        nowMs: Long,
        incoming: UnderstandingResult,
    ): NavigationCandidateSnapshot? = synchronized(lock) {
        if (cancelled || forSessionId == 0L || forSessionId != sessionId) return@synchronized null
        val incomingDest = destinationOf(incoming)
        val preferredDest = preferredComplete?.let { destinationOf(it) }.orEmpty()
        val relation = classify(preferredDest, incomingDest)
        lastRawPartial = incoming.rawTranscript
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
                    firstSeenMs = nowMs
                    blockedByIncompleteGrowth = false
                } else {
                    // Meaningful growth that is still an incomplete head (e.g. "số 25" →
                    // "số 25 phố") must reset the clock and block commit of the shorter dest.
                    blockedByIncompleteGrowth = preferredComplete != null &&
                        relation != NavigationCandidateRelation.NEW
                    if (blockedByIncompleteGrowth || preferredComplete == null) {
                        firstSeenMs = nowMs
                    }
                }
            }
        }
        val preferred = preferredComplete
        val dest = preferred?.let { destinationOf(it) }?.ifBlank { incomingDest } ?: incomingDest
        val completeness = NavigationCommitPolicy.completenessOf(dest)
        val stableFor = if (firstSeenMs < 0L) 0L else (nowMs - firstSeenMs).coerceAtLeast(0L)
        val snapshot = NavigationCandidateSnapshot(
            sessionId = sessionId,
            rawPartial = lastRawPartial,
            normalizedDestination = dest,
            tokenCount = dest.split(" ").filter { it.isNotEmpty() }.size,
            timestampMs = nowMs,
            relation = relation,
            completeness = completeness,
            stableForMs = stableFor,
            preferred = preferred,
        )
        logCandidate(snapshot, commitReason = "observe")
        snapshot
    }

    fun logCommit(nowMs: Long, reason: String, destination: String) {
        val snapshot = synchronized(lock) {
            NavigationCandidateSnapshot(
                sessionId = sessionId,
                rawPartial = lastRawPartial,
                normalizedDestination = destination,
                tokenCount = destination.split(" ").filter { it.isNotEmpty() }.size,
                timestampMs = nowMs,
                relation = lastRelation,
                completeness = NavigationCommitPolicy.completenessOf(destination),
                stableForMs = if (firstSeenMs < 0L) 0L else (nowMs - firstSeenMs).coerceAtLeast(0L),
                preferred = preferredComplete,
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

    private fun destinationOf(result: UnderstandingResult): String {
        return (result.command as? CanonicalCommand.Navigate)?.destination?.trim().orEmpty()
            .ifBlank { result.destination.orEmpty().trim() }
    }

    private fun logCandidate(snapshot: NavigationCandidateSnapshot, commitReason: String) {
        val line =
            "NAV_CANDIDATE SESSION_ID=${snapshot.sessionId} " +
                "RAW_PARTIAL=${snapshot.rawPartial} " +
                "NAV_CANDIDATE=${snapshot.normalizedDestination} " +
                "CANDIDATE_RELATION=${snapshot.relation} " +
                "NAV_STABLE_FOR_MS=${snapshot.stableForMs} " +
                "NAV_COMPLETENESS=${snapshot.completeness} " +
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
