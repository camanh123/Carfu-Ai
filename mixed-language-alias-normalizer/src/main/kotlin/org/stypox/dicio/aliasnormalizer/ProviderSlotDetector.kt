package org.stypox.dicio.aliasnormalizer

data class DetectedProviderSlot(
    val hasMediaCommand: Boolean,
    val preposition: String?,
    val prepositionEnd: Int,
    val candidate: String,
    val eligible: Boolean,
)

/**
 * Locates a complete provider slot after a media-command prefix and a
 * provider preposition. Does not fuzzy-match the song title.
 */
class ProviderSlotDetector {
    fun detect(transcript: String): DetectedProviderSlot {
        val tokens = TranscriptTokenizer.tokenize(transcript)
        if (tokens.isEmpty()) {
            return DetectedProviderSlot(false, null, 0, "", false)
        }
        val prepIndex = tokens.indices.lastOrNull { index ->
            TranscriptFolder.foldToken(tokens[index].text) in
                DefaultProviderAliasNormalizer.PROVIDER_PREPOSITIONS
        }
        if (prepIndex == null || prepIndex == tokens.lastIndex) {
            val media = MediaCommandContext.hasMediaCommand(tokens.map { it.text })
            return DetectedProviderSlot(media, null, 0, "", false)
        }
        val prefixTokens = tokens.subList(0, prepIndex)
        val media = MediaCommandContext.hasMediaCommand(prefixTokens.map { it.text })
        val slot = tokens.subList(prepIndex + 1, tokens.size)
        val candidate = transcript.substring(slot.first().start, slot.last().end)
        val prep = tokens[prepIndex].text
        return DetectedProviderSlot(
            hasMediaCommand = media,
            preposition = prep,
            prepositionEnd = tokens[prepIndex].end,
            candidate = candidate,
            eligible = media && candidate.isNotBlank(),
        )
    }
}
