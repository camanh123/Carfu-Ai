package org.stypox.dicio.io.session

/**
 * Phase 4.5 — compositional Vietnamese PLAY_MEDIA grammar.
 *
 * Not a list of full utterances. Tokens compose:
 *   [action] [filler] [media-noun] [query] [provider-prep] [provider]
 *
 * Grammar tokens are stripped only in those grammatical slots. Song-title
 * words (including "hát" inside a title) are never blindly deleted.
 */
object VietnameseMediaCommandGrammar {
    data class Analysis(
        val complete: Boolean,
        val reason: String,
        val queryRaw: String,
        val queryFolded: String,
        val providerLabel: String?,
        val providerFolded: String?,
        val leadingTokenCount: Int,
        val trailingTokenCount: Int,
        val knownProvider: Boolean = false,
    )

    val PROVIDER_LABELS: Map<String, String> = mapOf(
        "youtube" to "YouTube",
        "you tube" to "YouTube",
        "yt" to "YouTube",
        "smarttube" to "SmartTube",
        "smart tube" to "SmartTube",
        "musicloop" to "MusicLoop",
        "music loop" to "MusicLoop",
    )

    private val WHITESPACE = Regex("\\s+")

    private val LEGACY_MUSIC_CONTROL = setOf(
        "phat nhac",
        "bat nhac",
        "mo nhac",
        "tam dung nhac",
    )

    /** Longest first. */
    private val ACTIONS: List<List<String>> = listOf(
        listOf("cho", "toi", "nghe"),
        listOf("tim", "va", "phat"),
        listOf("cho", "nghe"),
        listOf("phat"),
        listOf("nghe"),
        listOf("tim"),
        listOf("mo"),
        listOf("bat"),
    ).sortedByDescending { it.size }

    private val MEDIA_ONLY_ACTIONS: Set<List<String>> = setOf(
        listOf("cho", "toi", "nghe"),
        listOf("tim", "va", "phat"),
        listOf("cho", "nghe"),
        listOf("phat"),
        listOf("nghe"),
        listOf("tim"),
    )

    private val SHARED_OPEN_ACTIONS: Set<List<String>> = setOf(
        listOf("mo"),
        listOf("bat"),
    )

    private val FILLERS: List<List<String>> = listOf(
        listOf("giup", "toi"),
        listOf("giup"),
    ).sortedByDescending { it.size }

    private val MEDIA_NOUNS: List<List<String>> = listOf(
        listOf("bai", "hat"),
        listOf("bai"),
        listOf("nhac"),
        listOf("video"),
        listOf("clip"),
    ).sortedByDescending { it.size }

    private val PROVIDER_PREPOSITIONS = setOf("tren", "bang", "qua", "voi", "tu")

    fun isKnownProviderLabel(provider: String?): Boolean {
        if (provider.isNullOrBlank()) return false
        val folded = VietnameseTranscript.foldForMatch(provider)
        return PROVIDER_LABELS.containsKey(folded) ||
            PROVIDER_LABELS.values.any { it.equals(provider, ignoreCase = true) }
    }

    fun resolveProviderLabel(folded: String): String? = PROVIDER_LABELS[folded.trim()]

    /** True when [folded] is a PLAY_MEDIA utterance (complete or incomplete). */
    fun isMediaCommand(folded: String): Boolean = parseFolded(folded) != null

    fun parse(raw: String, folded: String = VietnameseTranscript.foldForMatch(raw)): Analysis? {
        val analysis = parseFolded(folded) ?: return null
        val queryRaw = if (analysis.queryFolded.isEmpty()) {
            ""
        } else {
            sliceRawQuery(
                raw = raw,
                leading = analysis.leadingTokenCount,
                trailing = analysis.trailingTokenCount,
                queryFolded = analysis.queryFolded,
            )
        }
        val providerLabel = when {
            analysis.providerLabel != null -> analysis.providerLabel
            !analysis.providerFolded.isNullOrBlank() -> sliceRawTrailing(
                raw = raw,
                trailing = analysis.trailingTokenCount,
                folded = analysis.providerFolded,
            )
            else -> null
        }
        return analysis.copy(queryRaw = queryRaw, providerLabel = providerLabel)
    }

    fun parseFolded(folded: String): Analysis? {
        if (folded.isEmpty()) return null
        if (folded in LEGACY_MUSIC_CONTROL) return null
        if (folded.contains("bai tiep") || folded.contains("bai truoc")) return null

        val tokens = folded.split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null

        var index = 0
        val action = consumeLongest(tokens, index, ACTIONS)
        if (action != null) {
            index += action.size
        }
        val filler = consumeLongest(tokens, index, FILLERS)
        if (filler != null) {
            index += filler.size
        }
        val noun = consumeLongest(tokens, index, MEDIA_NOUNS)
        if (noun != null) {
            index += noun.size
        }
        val leading = index
        val rest = tokens.drop(index)

        val mediaOnly = action != null && action in MEDIA_ONLY_ACTIONS
        val sharedOpen = action != null && action in SHARED_OPEN_ACTIONS
        val hasNoun = noun != null
        val elliptical = action == null && filler == null
        val mediaClaim = mediaOnly || hasNoun

        val prepIndex = rest.indices.lastOrNull { rest[it] in PROVIDER_PREPOSITIONS }
        if (prepIndex != null) {
            val queryTokens = rest.take(prepIndex)
            val providerTokens = rest.drop(prepIndex + 1)
            val queryFolded = queryTokens.joinToString(" ")
            val trailing = rest.size - queryTokens.size // prep + provider
            if (providerTokens.isEmpty()) {
                if (mediaClaim || sharedOpen && queryFolded.isNotEmpty() || elliptical && queryFolded.isNotEmpty()) {
                    return incomplete(
                        reason = "media_missing_provider",
                        queryFolded = queryFolded,
                        leading = leading,
                        trailing = trailing,
                    )
                }
                return null
            }
            val providerFolded = providerTokens.joinToString(" ")
            val knownLabel = resolveProviderTokens(providerTokens)
            val known = knownLabel != null
            if (queryFolded.isEmpty() || !isMeaningfulQuery(queryFolded)) {
                if (mediaClaim || sharedOpen || elliptical) {
                    return incomplete(
                        reason = "media_missing_query",
                        providerLabel = knownLabel,
                        providerFolded = providerFolded,
                        leading = leading,
                        trailing = trailing,
                    )
                }
                return null
            }
            // Shared "mở <query> trên <provider>": media outranks OPEN_APP when
            // the provider slot is present. Unknown providers stay structurally
            // complete (early-commit still requires a known catalog provider).
            if (mediaClaim || sharedOpen || elliptical) {
                return Analysis(
                    complete = true,
                    reason = "media_complete",
                    queryRaw = "",
                    queryFolded = queryFolded,
                    providerLabel = knownLabel,
                    providerFolded = providerFolded,
                    leadingTokenCount = leading,
                    trailingTokenCount = trailing,
                    knownProvider = known,
                )
            }
        }

        if (rest.isEmpty()) {
            return if (mediaClaim) {
                incomplete(reason = "media_incomplete", leading = leading, trailing = 0)
            } else {
                null
            }
        }

        if (mediaClaim) {
            return incomplete(
                reason = "media_missing_provider",
                queryFolded = rest.joinToString(" "),
                leading = leading,
                trailing = 0,
            )
        }

        // "mở YouTube" / elliptical without a provider preposition is not media.
        return null
    }

    private fun incomplete(
        reason: String,
        queryFolded: String = "",
        providerLabel: String? = null,
        providerFolded: String? = null,
        leading: Int,
        trailing: Int,
    ): Analysis = Analysis(
        complete = false,
        reason = reason,
        queryRaw = "",
        queryFolded = queryFolded,
        providerLabel = providerLabel,
        providerFolded = providerFolded,
        leadingTokenCount = leading,
        trailingTokenCount = trailing,
        knownProvider = providerLabel != null,
    )

    private fun isMeaningfulQuery(queryFolded: String): Boolean {
        val q = queryFolded.trim()
        if (q.isEmpty()) return false
        if (q in PROVIDER_PREPOSITIONS) return false
        if (MEDIA_NOUNS.any { it.joinToString(" ") == q }) return false
        return q.length >= 2
    }

    private fun consumeLongest(
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

    private fun resolveProviderTokens(tokens: List<String>): String? {
        val max = minOf(3, tokens.size)
        for (n in max downTo 1) {
            val slice = tokens.take(n).joinToString(" ")
            PROVIDER_LABELS[slice]?.let { return it }
        }
        PROVIDER_LABELS[tokens.joinToString(" ")]?.let { return it }
        return null
    }

    private fun sliceRawQuery(
        raw: String,
        leading: Int,
        trailing: Int,
        queryFolded: String,
    ): String {
        val rawWords = WHITESPACE.split(raw.trim()).filter { it.isNotEmpty() }
        val queryWordCount = queryFolded.split(' ').filter { it.isNotEmpty() }.size
        if (rawWords.size >= leading + queryWordCount + trailing) {
            return rawWords.subList(leading, leading + queryWordCount).joinToString(" ")
        }
        return titleCase(queryFolded)
    }

    private fun sliceRawTrailing(raw: String, trailing: Int, folded: String): String {
        val rawWords = WHITESPACE.split(raw.trim()).filter { it.isNotEmpty() }
        val providerWordCount = folded.split(' ').filter { it.isNotEmpty() }.size
        // trailing includes the preposition plus provider tokens.
        if (rawWords.size >= trailing && providerWordCount > 0) {
            return rawWords.takeLast(providerWordCount).joinToString(" ")
        }
        return titleCase(folded)
    }

    private fun titleCase(folded: String): String =
        folded.split(' ').filter { it.isNotEmpty() }.joinToString(" ") { w ->
            w.replaceFirstChar { c -> c.uppercase() }
        }
}
