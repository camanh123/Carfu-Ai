package org.stypox.dicio.aliasnormalizer

/**
 * Lightweight deterministic phonetic key for short mixed Vietnamese/English
 * provider names. Generic confusion classes only — not a per-provider dictionary.
 */
object PhoneticNormalizer {
    fun encode(text: String): String {
        val folded = TranscriptFolder.fold(text)
        if (folded.isEmpty()) return ""
        return folded.split(' ').filter { it.isNotEmpty() }.joinToString(" ") { encodeToken(it) }
    }

    fun encodeCompact(text: String): String = encode(text).replace(" ", "")

    fun encodeToken(token: String): String {
        if (token.isEmpty()) return ""
        var s = TranscriptFolder.foldToken(token)
        s = s.replace("ph", "f").replace("kh", "k").replace("gh", "g")
            .replace("ng", "n").replace("nh", "n").replace("th", "t")
            .replace("tr", "t").replace("ch", "c").replace("gi", "z")
            .replace("qu", "k").replace("oo", "u").replace("ou", "u")
            .replace("ee", "i")
        val out = StringBuilder()
        for (idx in s.indices) {
            val c = s[idx]
            val mapped = when (c) {
                'b', 'p' -> 'P'
                'd', 't' -> 'T'
                'g', 'k', 'c', 'q' -> 'K'
                'f', 'v', 'w' -> 'F'
                's', 'z', 'x' -> 'S'
                'm', 'n' -> 'N'
                'l', 'r' -> 'L'
                'y' -> if (idx == 0) 'Y' else 'U'
                'h' -> continue
                'a', 'e', 'i', 'o', 'u' -> 'U'
                else -> continue
            }
            if (out.isEmpty() || out.last() != mapped) out.append(mapped)
        }
        while (out.length >= 4 && out.last() == 'U') {
            out.deleteCharAt(out.length - 1)
        }
        if (out.length in 1..4 && out.last() in STOP_CODA) {
            out.setCharAt(out.length - 1, '$')
        }
        return out.toString()
    }

    /** Variant that drops a prosthetic leading S (stood ~ tube). */
    fun encodeTokenDropLeadingS(token: String): String {
        val encoded = encodeToken(token)
        return if (encoded.length >= 3 && encoded[0] == 'S') encoded.substring(1) else encoded
    }

    private val STOP_CODA = setOf('P', 'T', 'K')
}
