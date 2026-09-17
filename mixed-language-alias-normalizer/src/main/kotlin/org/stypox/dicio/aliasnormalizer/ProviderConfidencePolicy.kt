package org.stypox.dicio.aliasnormalizer

/**
 * Centralized rewrite thresholds. Fail closed: MEDIUM/LOW/AMBIGUOUS never rewrite.
 */
object ProviderConfidencePolicy {
    const val PHONETIC_HIGH_MIN = 0.84
    const val LEXICAL_HIGH_MIN = 0.40
    const val FINAL_HIGH_MIN = 0.82
    const val FINAL_MEDIUM_MIN = 0.62
    const val PHONETIC_MEDIUM_MIN = 0.72
    const val CONTEXT_REQUIRED = 1.0
    const val AMBIGUITY_MARGIN = 0.08
    const val AMBIGUITY_FLOOR = 0.70
    const val LEXICAL_WEIGHT = 0.18
    const val PHONETIC_WEIGHT = 0.47
    const val CONTEXT_WEIGHT = 0.35

    fun combine(lexical: Double, phonetic: Double, context: Double): Double =
        LEXICAL_WEIGHT * lexical + PHONETIC_WEIGHT * phonetic + CONTEXT_WEIGHT * context

    fun confidence(
        exactCanonical: Boolean,
        exactAlias: Boolean,
        lexical: Double,
        phonetic: Double,
        context: Double,
        finalScore: Double,
        ambiguous: Boolean,
    ): Confidence {
        if (ambiguous) return Confidence.LOW
        if (exactCanonical || exactAlias) return Confidence.HIGH
        val highPhonetic = phonetic >= PHONETIC_HIGH_MIN &&
            lexical >= LEXICAL_HIGH_MIN &&
            context >= CONTEXT_REQUIRED &&
            finalScore >= FINAL_HIGH_MIN
        if (highPhonetic) return Confidence.HIGH
        if (phonetic >= PHONETIC_MEDIUM_MIN &&
            context >= CONTEXT_REQUIRED &&
            finalScore >= FINAL_MEDIUM_MIN
        ) {
            return Confidence.MEDIUM
        }
        return Confidence.LOW
    }

    fun shouldRewrite(
        confidence: Confidence,
        ambiguous: Boolean,
        candidateFolded: String,
        canonicalFolded: String,
    ): Boolean {
        if (confidence != Confidence.HIGH || ambiguous) return false
        if (canonicalFolded.isEmpty()) return false
        return candidateFolded != canonicalFolded
    }

    fun isAmbiguous(ranked: List<EntityScore>): Boolean {
        if (ranked.size < 2) return false
        val a = ranked[0]
        val b = ranked[1]
        if (a.exactCanonical || a.exactAlias != null) return false
        if (a.finalScore < AMBIGUITY_FLOOR || b.finalScore < AMBIGUITY_FLOOR) return false
        return a.finalScore - b.finalScore <= AMBIGUITY_MARGIN
    }
}
