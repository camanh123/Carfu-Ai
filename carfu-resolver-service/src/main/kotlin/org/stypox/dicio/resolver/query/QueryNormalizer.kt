package org.stypox.dicio.resolver.query

import java.text.Normalizer

data class NormalizedQuery(
    val original: String,
    val searchQuery: String,
    val folded: String,
    val tokens: List<String>,
    val coreTokens: List<String>,
    val variants: RequestedVariants,
)

object QueryNormalizer {
    fun normalize(raw: String): NormalizedQuery {
        val original = raw.trim().replace(WHITESPACE, " ")
        val folded = fold(original)
        val tokens = tokenize(folded)
        val variants = VariantDetector.detectRequested(folded)
        val coreTokens = tokens.filterNot { it in VariantDetector.STRIP_FROM_CORE }
        return NormalizedQuery(
            original = original,
            searchQuery = original,
            folded = folded,
            tokens = tokens,
            coreTokens = coreTokens,
            variants = variants,
        )
    }

    fun fold(value: String): String {
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
        val withoutMarks = MARKS.replace(decomposed, "")
        return withoutMarks
            .replace('đ', 'd')
            .replace('Đ', 'd')
            .lowercase()
            .replace(NON_ALNUM, " ")
            .trim()
            .replace(WHITESPACE, " ")
    }

    fun tokenize(folded: String): List<String> =
        folded.split(WHITESPACE).filter { it.isNotEmpty() }

    private val WHITESPACE = Regex("\\s+")
    private val MARKS = Regex("\\p{M}+")
    private val NON_ALNUM = Regex("[^a-z0-9#]+")
}
