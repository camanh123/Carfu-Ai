package org.stypox.dicio.aliasnormalizer

/**
 * Supporting evidence only. A lexical resemblance never authorizes rewrite
 * by itself — [ProviderConfidencePolicy] requires phonetic + context too.
 */
class LexicalSimilarityScorer {
    fun score(candidate: String, entity: ProviderEntity): Double {
        val folded = TranscriptFolder.fold(candidate)
        val names = buildList {
            add(entity.foldedCanonical)
            add(entity.spacedCanonical)
            addAll(entity.foldedAliases)
        }.distinct()
        val stringBest = names.maxOf { StringSimilarity.normalized(folded, it) }
        val candTokens = folded.split(' ').filter { it.isNotEmpty() }
        val canonTokens = entity.spacedCanonical.split(' ').filter { it.isNotEmpty() }
        val tokenScore = tokenAlignment(candTokens, canonTokens)
        return maxOf(stringBest, tokenScore)
    }

    private fun tokenAlignment(candidate: List<String>, canonical: List<String>): Double {
        if (candidate.isEmpty() || canonical.isEmpty()) return 0.0
        if (candidate.size == canonical.size) {
            return candidate.zip(canonical).map { (a, b) ->
                StringSimilarity.normalized(a, b)
            }.average()
        }
        val inter = candidate.toSet().intersect(canonical.toSet()).size
        val union = candidate.toSet().union(canonical.toSet()).size
        return if (union == 0) 0.0 else inter.toDouble() / union
    }
}
