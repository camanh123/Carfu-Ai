package org.stypox.dicio.aliasnormalizer

enum class Confidence { HIGH, MEDIUM, LOW }

enum class MatchType {
    NONE,
    EXACT_CANONICAL,
    EXACT_ALIAS,
    PHONETIC,
    MIXED,
}

data class EntityScore(
    val canonicalProvider: String,
    val exactCanonical: Boolean,
    val exactAlias: String?,
    val lexicalScore: Double,
    val phoneticScore: Double,
    val contextScore: Double,
    val finalScore: Double,
)

/**
 * Explainable provider-slot resolution. No command execution.
 */
data class ProviderResolution(
    val originalTranscript: String,
    val mediaCommand: Boolean,
    val providerSlotDetected: Boolean,
    val originalCandidate: String?,
    val canonicalProvider: String?,
    val exactMatch: Boolean,
    val exactAliasMatch: Boolean,
    val matchedAlias: String?,
    val lexicalScore: Double,
    val phoneticScore: Double,
    val contextScore: Double,
    val finalScore: Double,
    val confidence: Confidence,
    val matchType: MatchType,
    val ambiguous: Boolean,
    val ambiguousWith: List<String>,
    val shouldRewrite: Boolean,
    val reason: String,
    val evaluatedEntities: List<EntityScore>,
    val rewrittenTranscript: String? = null,
) {
    companion object {
        fun ineligible(
            transcript: String,
            mediaCommand: Boolean,
            providerSlotDetected: Boolean,
            reason: String,
            candidate: String? = null,
        ) = ProviderResolution(
            originalTranscript = transcript,
            mediaCommand = mediaCommand,
            providerSlotDetected = providerSlotDetected,
            originalCandidate = candidate,
            canonicalProvider = null,
            exactMatch = false,
            exactAliasMatch = false,
            matchedAlias = null,
            lexicalScore = 0.0,
            phoneticScore = 0.0,
            contextScore = 0.0,
            finalScore = 0.0,
            confidence = Confidence.LOW,
            matchType = MatchType.NONE,
            ambiguous = false,
            ambiguousWith = emptyList(),
            shouldRewrite = false,
            reason = reason,
            evaluatedEntities = emptyList(),
        )
    }
}
