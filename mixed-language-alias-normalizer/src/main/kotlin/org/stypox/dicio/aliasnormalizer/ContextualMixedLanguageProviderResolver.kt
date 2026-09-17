package org.stypox.dicio.aliasnormalizer

/**
 * Contextual mixed Vietnamese/English provider resolver.
 *
 * Scores only the provider-slot candidate of a media command. Never
 * fuzzy-matches the full transcript or the song title.
 */
class ContextualMixedLanguageProviderResolver(
    private val catalog: ProviderEntityCatalog = ProviderEntityCatalog(),
    private val slotDetector: ProviderSlotDetector = ProviderSlotDetector(),
    private val exactMatcher: ExactProviderMatcher = ExactProviderMatcher(catalog),
    private val phoneticMatcher: PhoneticMatcher = PhoneticMatcher(),
    private val lexicalScorer: LexicalSimilarityScorer = LexicalSimilarityScorer(),
) {
    fun resolve(transcript: String): ProviderResolution {
        val slot = slotDetector.detect(transcript)
        if (!slot.eligible) {
            val reason = when {
                !slot.hasMediaCommand && slot.preposition == null ->
                    "no_media_command_and_no_provider_slot"
                !slot.hasMediaCommand -> "no_media_command_context"
                else -> "no_provider_slot"
            }
            return ProviderResolution.ineligible(
                transcript = transcript,
                mediaCommand = slot.hasMediaCommand,
                providerSlotDetected = slot.preposition != null && slot.candidate.isNotBlank(),
                reason = reason,
                candidate = slot.candidate.ifBlank { null },
            )
        }
        val candidate = slot.candidate
        val contextScore = ProviderConfidencePolicy.CONTEXT_REQUIRED
        val exact = exactMatcher.match(candidate)
        val scores = catalog.all().map { entity ->
            val hit = exact?.takeIf { it.entity.canonicalName == entity.canonicalName }
            val exactCanonical = hit?.canonical == true
            val exactAlias = hit != null && !hit.canonical
            val lexical = if (hit != null) 1.0 else lexicalScorer.score(candidate, entity)
            val phonetic = if (hit != null) 1.0 else phoneticMatcher.score(candidate, entity)
            val finalScore = if (hit != null) {
                1.0
            } else {
                ProviderConfidencePolicy.combine(lexical, phonetic, contextScore)
            }
            EntityScore(
                canonicalProvider = entity.canonicalName,
                exactCanonical = exactCanonical,
                exactAlias = hit?.alias.takeIf { exactAlias },
                lexicalScore = lexical,
                phoneticScore = phonetic,
                contextScore = contextScore,
                finalScore = finalScore,
            )
        }.sortedByDescending { it.finalScore }

        val ambiguous = ProviderConfidencePolicy.isAmbiguous(scores)
        val top = scores.first()
        val confidence = ProviderConfidencePolicy.confidence(
            exactCanonical = top.exactCanonical,
            exactAlias = top.exactAlias != null,
            lexical = top.lexicalScore,
            phonetic = top.phoneticScore,
            context = top.contextScore,
            finalScore = top.finalScore,
            ambiguous = ambiguous,
        )
        val matchType = when {
            top.exactCanonical -> MatchType.EXACT_CANONICAL
            top.exactAlias != null -> MatchType.EXACT_ALIAS
            confidence == Confidence.HIGH -> MatchType.PHONETIC
            top.phoneticScore >= top.lexicalScore -> MatchType.PHONETIC
            top.lexicalScore > 0.0 -> MatchType.MIXED
            else -> MatchType.NONE
        }
        val canonical = top.canonicalProvider.takeIf { confidence == Confidence.HIGH && !ambiguous }
        val shouldRewrite = canonical != null && ProviderConfidencePolicy.shouldRewrite(
            confidence = confidence,
            ambiguous = ambiguous,
            candidateFolded = TranscriptFolder.fold(candidate),
            canonicalFolded = TranscriptFolder.fold(canonical),
        )
        val rewritten = if (shouldRewrite) {
            val prefix = transcript.substring(0, slot.prepositionEnd)
            "$prefix $canonical"
        } else {
            null
        }
        val reason = when {
            ambiguous -> "ambiguous_providers"
            confidence == Confidence.HIGH && shouldRewrite -> "high_confidence_rewrite"
            confidence == Confidence.HIGH -> "high_confidence_already_canonical"
            confidence == Confidence.MEDIUM -> "medium_no_rewrite"
            else -> "low_unknown_no_rewrite"
        }
        return ProviderResolution(
            originalTranscript = transcript,
            mediaCommand = true,
            providerSlotDetected = true,
            originalCandidate = candidate,
            canonicalProvider = canonical,
            exactMatch = top.exactCanonical,
            exactAliasMatch = top.exactAlias != null,
            matchedAlias = top.exactAlias,
            lexicalScore = top.lexicalScore,
            phoneticScore = top.phoneticScore,
            contextScore = top.contextScore,
            finalScore = top.finalScore,
            confidence = confidence,
            matchType = matchType,
            ambiguous = ambiguous,
            ambiguousWith = if (ambiguous) scores.drop(1).map { it.canonicalProvider } else emptyList(),
            shouldRewrite = shouldRewrite,
            reason = reason,
            evaluatedEntities = scores,
            rewrittenTranscript = rewritten,
        )
    }
}
