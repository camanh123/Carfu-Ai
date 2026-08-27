package org.stypox.dicio.io.session

import org.dicio.skill.standard.util.nfkdNormalizeWord
import java.util.Locale

/**
 * Post-STT text handling for Vietnamese command routing.
 * Matching uses the folded form; the UI always keeps [original].
 */
data class VietnameseTranscript(
    val original: String,
    val display: String,
    val folded: String,
    val words: List<String>,
) {
    val isEmpty: Boolean get() = folded.isEmpty()

    companion object {
        private val WHITESPACE = Regex("\\s+")
        private val PUNCTUATION = Regex("[\\p{Punct}¿¡…]+")
        private val ECHO_PHRASES = setOf(
            "toi nghe day",
            "toi nghe day?",
            "toi nghe",
            "nghe day",
            "em nghe day",
            "em nghe day a",
        )

        fun parse(raw: String): VietnameseTranscript {
            val display = raw
                .trim()
                .replace(WHITESPACE, " ")
            val folded = foldForMatch(display)
            val words = if (folded.isEmpty()) emptyList() else folded.split(' ')
            return VietnameseTranscript(
                original = raw,
                display = display,
                folded = folded,
                words = words,
            )
        }

        fun foldForMatch(text: String): String {
            val lowered = text.lowercase(Locale.ROOT)
                .replace('đ', 'd')
                .replace(PUNCTUATION, " ")
            val collapsed = WHITESPACE.replace(lowered, " ").trim()
            if (collapsed.isEmpty()) return ""
            return collapsed.split(' ').joinToString(" ") { nfkdNormalizeWord(it) }
        }

        /**
         * Empty, TTS-echo, or ultra-short noise fragments must not be submitted as commands.
         */
        fun isTooWeakToSubmit(raw: String): Boolean {
            val parsed = parse(raw)
            if (parsed.isEmpty) return true
            if (parsed.folded in ECHO_PHRASES) return true
            if (parsed.folded.startsWith("toi nghe")) return true
            if (parsed.words.size == 1 && parsed.folded.length <= 5) return true
            // Isolated cabin-echo fragments such as "hà hồ" must not become commands.
            if (parsed.folded.length < 6) return true
            return false
        }
    }
}
