package org.stypox.dicio.aliasnormalizer

/**
 * Deterministic provider-alias rewrite. Not a spell checker.
 *
 * Replaces a provider slot only when it sits after a media-provider
 * preposition used by production Media NLU (`trên` / `bằng` / `qua` /
 * `với` / `từ`) and the entire slot matches a registry alias.
 */
fun interface ProviderAliasNormalizer {
    fun normalize(transcript: String): NormalizationResult
}

class DefaultProviderAliasNormalizer(
    private val registry: ProviderAliasRegistry = ProviderAliasRegistry(),
) : ProviderAliasNormalizer {

    override fun normalize(transcript: String): NormalizationResult {
        val tokens = tokenize(transcript)
        if (tokens.isEmpty()) {
            return unchanged(transcript)
        }
        val prepIndex = tokens.indices.lastOrNull { index ->
            TranscriptFolder.foldToken(tokens[index].text) in PROVIDER_PREPOSITIONS
        } ?: return unchanged(transcript)
        if (prepIndex == tokens.lastIndex) {
            return unchanged(transcript)
        }
        val slot = tokens.subList(prepIndex + 1, tokens.size)
        val foldedSlot = slot.joinToString(" ") { TranscriptFolder.foldToken(it.text) }
        if (foldedSlot.isEmpty()) {
            return unchanged(transcript)
        }
        val alias = registry.findExact(foldedSlot) ?: return unchanged(transcript)
        val alreadyCanonical = slot.size == 1 && slot[0].text == alias.canonicalProvider
        if (alreadyCanonical) {
            return NormalizationResult(
                originalTranscript = transcript,
                normalizedTranscript = transcript,
                providerDetected = alias.canonicalProvider,
                aliasMatched = alias.observedForm,
                changed = false,
            )
        }
        val prefix = transcript.substring(0, tokens[prepIndex].end)
        val rewritten = "$prefix ${alias.canonicalProvider}"
        return NormalizationResult(
            originalTranscript = transcript,
            normalizedTranscript = rewritten,
            providerDetected = alias.canonicalProvider,
            aliasMatched = alias.observedForm,
            changed = rewritten != transcript,
        )
    }

    private fun unchanged(transcript: String) = NormalizationResult(
        originalTranscript = transcript,
        normalizedTranscript = transcript,
        providerDetected = null,
        aliasMatched = null,
        changed = false,
    )

    private data class Token(val text: String, val start: Int, val end: Int)

    private fun tokenize(text: String): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        while (i < text.length) {
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length) break
            val start = i
            while (i < text.length && !text[i].isWhitespace()) i++
            out += Token(text.substring(start, i), start, i)
        }
        return out
    }

    companion object {
        /**
         * Folded forms of production Media NLU provider prepositions:
         * trên, bằng, qua, với, từ.
         */
        val PROVIDER_PREPOSITIONS: Set<String> = setOf("tren", "bang", "qua", "voi", "tu")
    }
}
