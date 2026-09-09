package org.stypox.dicio.youtubeplayauto

/**
 * Title matching against YouTube result rows. Not Vietnamese command parsing.
 */
object YouTubeTitleMatcher {
    fun normalize(raw: String): String = raw.trim().lowercase()

    fun tokens(raw: String): List<String> =
        normalize(raw)
            .split(Regex("[\\s\\-_–,./|:;]+"))
            .filter { it.length >= 3 }

    fun score(title: String, query: String): Int {
        val nTitle = normalize(title)
        val nQuery = normalize(query)
        if (nQuery.isEmpty() || nTitle.isEmpty()) return 0
        if (nTitle.contains(nQuery)) return 100
        val queryTokens = tokens(query)
        if (queryTokens.isEmpty()) return 0
        val hits = queryTokens.count { nTitle.contains(it) }
        if (hits == 0) return 0
        return (hits * 100) / queryTokens.size
    }

    fun isSelectable(score: Int): Boolean = score >= 50
}
