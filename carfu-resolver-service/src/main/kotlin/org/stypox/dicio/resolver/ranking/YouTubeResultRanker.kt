package org.stypox.dicio.resolver.ranking

import org.stypox.dicio.resolver.query.NormalizedQuery
import org.stypox.dicio.resolver.query.QueryNormalizer
import org.stypox.dicio.resolver.query.TitleSignals
import org.stypox.dicio.resolver.query.VariantDetector
import org.stypox.dicio.resolver.query.VariantKind
import kotlin.math.ln
import kotlin.math.min

data class RankedPick(
    val candidate: YouTubeCandidate,
    val score: Double,
)

/**
 * Deterministic, no-LLM ranker. Never returns items[0] unless that item
 * independently clears [MIN_SCORE] and a minimum title-overlap gate.
 */
object YouTubeResultRanker {
    const val MIN_SCORE = 40.0
    const val MIN_TITLE_OVERLAP = 0.4

    fun pick(query: NormalizedQuery, candidates: List<YouTubeCandidate>): RankedPick? {
        var best: RankedPick? = null
        for (candidate in candidates) {
            val scored = score(query, candidate) ?: continue
            val current = best
            if (current == null ||
                scored.score > current.score ||
                (scored.score == current.score && scored.candidate.videoId < current.candidate.videoId)
            ) {
                best = scored
            }
        }
        return best
    }

    fun score(query: NormalizedQuery, candidate: YouTubeCandidate): RankedPick? {
        val titleFolded = QueryNormalizer.fold(candidate.title)
        val channelFolded = QueryNormalizer.fold(candidate.channelTitle)
        val titleTokens = QueryNormalizer.tokenize(titleFolded)
        val channelTokens = QueryNormalizer.tokenize(channelFolded).toSet()
        val titleTokenSet = titleTokens.toSet()
        val signals = VariantDetector.detectTitle(titleFolded)
        val coreQuery = query.coreTokens
        if (coreQuery.isEmpty()) return null

        val overlap = overlapRatio(coreQuery, titleTokenSet)
        val phrase = containsPhrase(titleTokens, coreQuery)
        val titleExact = QueryNormalizer.tokenize(
            titleFolded.split(' ').filterNot { it in VariantDetector.STRIP_FROM_CORE }.joinToString(" "),
        ) == coreQuery

        if (!phrase && overlap < MIN_TITLE_OVERLAP) {
            return null
        }

        var score = 0.0
        score += when {
            titleExact -> 100.0
            phrase -> 80.0
            overlap >= 0.99 -> 50.0
            else -> overlap * 40.0
        }

        val artistHits = query.coreTokens.count { token ->
            token in channelTokens && token !in titleTokenSet
        } + query.tokens.count { token ->
            token in channelTokens && token !in VariantDetector.STRIP_FROM_CORE
        }.let { min(it, 3) }
        score += min(24.0, artistHits * 8.0)

        val requested = query.variants.requestedKinds()
        for (kind in requested) {
            if (kind == VariantKind.VIDEO) {
                if (signals.has(kind)) score += 8.0
                continue
            }
            if (signals.has(kind) || (kind == VariantKind.OFFICIAL && signals.officialMarker)) {
                score += 40.0
            } else {
                score -= 30.0
            }
        }

        if (signals.officialMarker) score += 18.0
        if (signals.officialMv) score += 8.0
        if (signals.officialAudio) score += 8.0

        if (channelFolded.contains("official") || channelFolded.contains("vevo")) {
            score += 6.0
        }

        val views = candidate.viewCount
        if (views != null && views > 0) {
            score += min(5.0, ln(1.0 + views) / 4.0)
        }

        if (signals.shorts || isShortDuration(candidate)) {
            score -= 28.0
        }

        score += unrequestedPenalties(requested, signals)

        if (score < MIN_SCORE) return null
        return RankedPick(candidate, score)
    }

    private fun isShortDuration(candidate: YouTubeCandidate): Boolean {
        val duration = candidate.durationSeconds ?: return false
        return duration in 1..60
    }

    private fun unrequestedPenalties(requested: Set<VariantKind>, signals: TitleSignals): Double {
        var penalty = 0.0
        fun penalize(kind: VariantKind, amount: Double) {
            if (kind !in requested && signals.has(kind)) penalty += amount
        }
        penalize(VariantKind.KARAOKE, -42.0)
        penalize(VariantKind.REMIX, -36.0)
        penalize(VariantKind.COVER, -32.0)
        penalize(VariantKind.REACTION, -42.0)
        penalize(VariantKind.LIVE, -26.0)
        penalize(VariantKind.SPED_UP, -32.0)
        penalize(VariantKind.SLOWED, -32.0)
        penalize(VariantKind.NIGHTCORE, -32.0)
        penalize(VariantKind.INSTRUMENTAL, -26.0)
        penalize(VariantKind.BEAT, -20.0)
        return penalty
    }

    private fun overlapRatio(needle: List<String>, haystack: Set<String>): Double {
        if (needle.isEmpty()) return 0.0
        return needle.count { it in haystack }.toDouble() / needle.size
    }

    private fun containsPhrase(haystack: List<String>, needle: List<String>): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        for (i in 0..haystack.size - needle.size) {
            if (haystack.subList(i, i + needle.size) == needle) return true
        }
        return false
    }
}
