package org.stypox.dicio.aliasnormalizer

/**
 * Deterministic provider-alias rewrite. Not a spell checker.
 *
 * Replaces a provider slot only when ALL of:
 *  1. the prefix is a media-command construction (action + query, not
 *     a bare YouTube / Maps open-app phrase)
 *  2. a media-provider preposition (`trên` / `bằng` / `qua` / `với` /
 *     `từ`) introduces the slot
 *  3. [ContextualMixedLanguageProviderResolver] returns HIGH confidence
 *     and shouldRewrite
 *
 * Ambiguous forms such as "smartphone" / "smart YouTube" / "cùng một
 * chút" are never globally replaced. Spotify never becomes SmartTube.
 */
fun interface ProviderAliasNormalizer {
    fun normalize(transcript: String): NormalizationResult
}

class DefaultProviderAliasNormalizer(
    private val resolver: ContextualMixedLanguageProviderResolver =
        ContextualMixedLanguageProviderResolver(),
) : ProviderAliasNormalizer {

    override fun normalize(transcript: String): NormalizationResult {
        val resolved = resolver.resolve(transcript)
        return NormalizationResult(
            originalTranscript = transcript,
            normalizedTranscript = resolved.rewrittenTranscript ?: transcript,
            providerDetected = resolved.canonicalProvider,
            aliasMatched = resolved.matchedAlias,
            changed = resolved.shouldRewrite,
        )
    }

    fun resolve(transcript: String): ProviderResolution = resolver.resolve(transcript)

    companion object {
        /**
         * Folded forms of production Media NLU provider prepositions:
         * trên, bằng, qua, với, từ.
         */
        val PROVIDER_PREPOSITIONS: Set<String> = setOf("tren", "bang", "qua", "voi", "tu")
    }
}
