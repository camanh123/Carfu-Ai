package org.stypox.dicio.aliasnormalizer

import java.text.Normalizer
import java.util.Locale

/**
 * Match-only folding, local to this module. Does not import production NLU.
 *
 * Mirrors the production STT fold shape (lowercase, đ→d, punct→space, NFKD
 * diacritic strip) so registry aliases line up with Vietnamese STT tokens.
 */
internal object TranscriptFolder {
    private val WHITESPACE = Regex("\\s+")
    private val PUNCTUATION = Regex("[\\p{Punct}¿¡…]+")
    private val COMBINING = Regex("\\p{InCombiningDiacriticalMarks}+")

    fun fold(text: String): String {
        val lowered = text.lowercase(Locale.ROOT)
            .replace('đ', 'd')
            .replace(PUNCTUATION, " ")
        val collapsed = WHITESPACE.replace(lowered, " ").trim()
        if (collapsed.isEmpty()) return ""
        return collapsed.split(' ').joinToString(" ") { stripMarks(it) }
    }

    fun foldToken(token: String): String = fold(token)

    private fun stripMarks(word: String): String {
        val nfkd = Normalizer.normalize(word, Normalizer.Form.NFKD)
        return COMBINING.replace(nfkd, "")
    }
}
