package org.stypox.dicio.aliasnormalizer

/**
 * Conservative media-command context for provider-alias rewrites.
 *
 * Local to this module. Does not import production Media NLU.
 *
 * A rewrite requires a play-media construction before the provider
 * preposition: a media action, optional media noun, and a query that is
 * not a bare YouTube / Maps open-app phrase.
 */
internal object MediaCommandContext {
    val ACTIONS: List<List<String>> = listOf(
        listOf("cho", "toi", "nghe"),
        listOf("tim", "va", "phat"),
        listOf("cho", "nghe"),
        listOf("phat"),
        listOf("nghe"),
        listOf("play"),
        listOf("tim"),
        listOf("mo"),
        listOf("bat"),
    ).sortedByDescending { it.size }

    val FILLERS: List<List<String>> = listOf(
        listOf("giup", "toi"),
        listOf("giup"),
    ).sortedByDescending { it.size }

    val MEDIA_NOUNS: List<List<String>> = listOf(
        listOf("bai", "hat"),
        listOf("bai"),
        listOf("nhac"),
        listOf("video"),
        listOf("clip"),
        listOf("mv"),
    ).sortedByDescending { it.size }

    /** Bare queries that mean "open this app", not a song title. */
    val NON_MEDIA_QUERIES: Set<String> = setOf(
        "youtube",
        "you tube",
        "yt",
        "google maps",
        "google map",
        "maps",
    )

    private val SEARCH_ONLY_ACTION = listOf("tim")

    fun hasMediaCommand(prefixTokens: List<String>): Boolean {
        val folded = prefixTokens.map { TranscriptFolder.foldToken(it) }.filter { it.isNotEmpty() }
        if (folded.isEmpty()) return false
        var index = 0
        val action = consumeLongest(folded, index, ACTIONS) ?: return false
        index += action.size
        val filler = consumeLongest(folded, index, FILLERS)
        if (filler != null) index += filler.size
        val noun = consumeLongest(folded, index, MEDIA_NOUNS)
        if (noun != null) index += noun.size
        val query = folded.drop(index).joinToString(" ")
        if (query.isEmpty()) return false
        if (query in NON_MEDIA_QUERIES) return false
        if (action == SEARCH_ONLY_ACTION && noun == null) return false
        return true
    }

    internal fun consumeLongest(
        tokens: List<String>,
        index: Int,
        candidates: List<List<String>>,
    ): List<String>? {
        if (index >= tokens.size) return null
        for (candidate in candidates) {
            if (index + candidate.size > tokens.size) continue
            var ok = true
            for (i in candidate.indices) {
                if (tokens[index + i] != candidate[i]) {
                    ok = false
                    break
                }
            }
            if (ok) return candidate
        }
        return null
    }
}
