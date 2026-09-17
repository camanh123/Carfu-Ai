package org.stypox.dicio.aliasnormalizer

internal object TranscriptTokenizer {
    data class Token(val text: String, val start: Int, val end: Int)

    fun tokenize(text: String): List<Token> {
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
}
