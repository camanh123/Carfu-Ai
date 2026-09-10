package org.stypox.dicio.youtubeplayauto

/**
 * Android-free node snapshot for YouTube search UI classification.
 */
data class YouTubeNodeFact(
    val className: String = "",
    val text: String = "",
    val contentDescription: String = "",
    val viewId: String = "",
    val clickable: Boolean = false,
    val focused: Boolean = false,
    val editable: Boolean = false,
    val hasImeEnterAction: Boolean = false,
    val index: Int = -1,
    val parentIndex: Int? = null,
) {
    val combined: String = listOf(text, contentDescription).filter { it.isNotBlank() }.joinToString(" ")
}

data class YouTubeScoredResult(
    val node: YouTubeNodeFact,
    val title: String,
    val score: Int,
)

data class YouTubeUiFacts(
    val query: String,
    val submitted: Boolean,
    val nodes: List<YouTubeNodeFact>,
)

/**
 * Distinguishes YouTube's typed-search/suggestion screen from an actual results list.
 * Suggestions (remix/karaoke/history) are not video rows.
 */
object YouTubeSearchUiClassifier {
    private val DURATION = Regex("""\b\d{1,2}:\d{2}\b""")
    private val SUBMIT_LABELS = setOf(
        "search", "tìm kiếm", "tìm", "go", "enter", "done", "gửi",
    )
    private val FILTER_CHIPS = setOf("all", "videos", "video", "tất cả")
    private val SUGGESTION_EXTRAS = listOf(
        "remix", "karaoke", "lyrics", "lyric", "beat", "cover", "tiktok", "slow",
    )

    fun kind(facts: YouTubeUiFacts): YouTubeSearchUiKind {
        val focusedEdit = facts.nodes.any { it.focused && it.editable }
        val suggestionRows = suggestionRows(facts)
        val videoRows = videoRows(facts)
        val hasFilters = facts.nodes.any { FILTER_CHIPS.contains(YouTubeTitleMatcher.normalize(it.combined)) }
        if (focusedEdit && videoRows.isEmpty()) {
            return YouTubeSearchUiKind.SEARCH_INPUT_OR_SUGGESTIONS
        }
        if (videoRows.isNotEmpty()) return YouTubeSearchUiKind.RESULTS_LIST
        if (facts.submitted && !focusedEdit && (hasFilters || nonChromeTitles(facts).isNotEmpty())) {
            return YouTubeSearchUiKind.RESULTS_LIST
        }
        if (focusedEdit || suggestionRows.isNotEmpty()) {
            return YouTubeSearchUiKind.SEARCH_INPUT_OR_SUGGESTIONS
        }
        return YouTubeSearchUiKind.UNKNOWN
    }

    fun queryEntered(facts: YouTubeUiFacts): Boolean {
        val q = YouTubeTitleMatcher.normalize(facts.query)
        if (q.isEmpty()) return false
        return facts.nodes.any { node ->
            node.editable && YouTubeTitleMatcher.normalize(node.text).contains(q)
        }
    }

    fun chooseSubmitMethod(facts: YouTubeUiFacts): YouTubeSearchSubmitMethod {
        val searchField = searchField(facts)
        if (searchField?.hasImeEnterAction == true) return YouTubeSearchSubmitMethod.IME
        if (submitButton(facts) != null) return YouTubeSearchSubmitMethod.ACCESSIBILITY_BUTTON
        if (imeSearchKey(facts) != null) return YouTubeSearchSubmitMethod.IME
        return YouTubeSearchSubmitMethod.OTHER
    }

    fun searchField(facts: YouTubeUiFacts): YouTubeNodeFact? =
        facts.nodes.firstOrNull { it.focused && it.editable }
            ?: facts.nodes.firstOrNull { it.editable && it.viewId.contains("search") }
            ?: facts.nodes.firstOrNull { it.editable }

    fun submitButton(facts: YouTubeUiFacts): YouTubeNodeFact? =
        facts.nodes.firstOrNull { node ->
            node.clickable &&
                !node.editable &&
                isSubmitLabel(node.combined) &&
                !isSuggestionText(node.combined, facts.query)
        }

    fun imeSearchKey(facts: YouTubeUiFacts): YouTubeNodeFact? =
        facts.nodes.firstOrNull { node ->
            node.clickable &&
                isSubmitLabel(node.combined) &&
                (node.className.contains("Key") || node.viewId.contains("inputmethod"))
        }

    fun suggestionRows(facts: YouTubeUiFacts): List<String> =
        facts.nodes.map { it.combined }.filter { isSuggestionText(it, facts.query) }

    fun videoRows(facts: YouTubeUiFacts): List<String> =
        facts.nodes.map { it.combined }.filter { isVideoResultText(it) }

    fun candidateVideoTitles(facts: YouTubeUiFacts): List<String> {
        return candidateResultEntries(facts).map { it.title }
    }

    /**
     * Results-page candidates. Title-only rows are eligible; autocomplete
     * [isSuggestionText] is not applied here (that filter is search-box only).
     */
    fun candidateResultEntries(facts: YouTubeUiFacts): List<YouTubeScoredResult> {
        return facts.nodes.mapNotNull { node ->
            if (!isEligibleResultNode(node, facts.query)) return@mapNotNull null
            val title = node.combined
            val score = YouTubeTitleMatcher.score(title, facts.query) -
                penalizeUnwantedCompletion(title, facts.query)
            if (!YouTubeTitleMatcher.isSelectable(score)) return@mapNotNull null
            YouTubeScoredResult(node = node, title = title, score = score)
        }
    }

    fun isEligibleResultNode(node: YouTubeNodeFact, query: String): Boolean {
        if (node.editable) return false
        if (YouTubeVideoClickResolver.isKeyboardNode(node)) return false
        if (node.className.contains("EditText") || node.className.contains("SearchView")) return false
        val id = node.viewId.lowercase()
        if (id.contains("shorts")) return false
        val title = node.combined
        if (title.isBlank()) return false
        if (YouTubeSearchResultPicker.shouldSkipText(title)) return false
        if (isSubmitLabel(title)) return false
        val normalized = YouTubeTitleMatcher.normalize(title)
        if (normalized in FILTER_CHIPS) return false
        if (isChannelNameNode(node, query)) return false
        return true
    }

    fun isChannelNameNode(node: YouTubeNodeFact, query: String): Boolean {
        val id = node.viewId.lowercase()
        val cls = node.className.lowercase()
        val looksLikeChannel = id.contains("channel") ||
            cls.contains("channel") ||
            YouTubeTitleMatcher.normalize(node.combined).startsWith("by ")
        if (!looksLikeChannel) return false
        return YouTubeTitleMatcher.score(node.combined, query) < 50
    }

    fun nonChromeTitles(facts: YouTubeUiFacts): List<String> =
        facts.nodes.map { it.combined }.filter { text ->
            text.isNotBlank() &&
                !YouTubeSearchResultPicker.shouldSkipText(text) &&
                !isSubmitLabel(text)
        }

    fun isSuggestionText(text: String, query: String): Boolean {
        if (YouTubeSearchResultPicker.shouldSkipText(text)) return false
        if (isVideoResultText(text)) return false
        val n = YouTubeTitleMatcher.normalize(text)
        val q = YouTubeTitleMatcher.normalize(query)
        if (q.isEmpty() || n.isEmpty()) return false
        if (n == q) return true
        if (!n.startsWith(q)) return false
        val extra = n.removePrefix(q).trim { it <= ' ' || it == '-' || it == '–' }
        if (extra.isEmpty()) return true
        if (SUGGESTION_EXTRAS.any { extra.startsWith(it) || extra.contains(it) }) return true
        return extra.length in 1..28 &&
            !extra.contains("official") &&
            !extra.contains("audio") &&
            !extra.contains("mv")
    }

    fun isVideoResultText(text: String): Boolean {
        if (text.isBlank()) return false
        return DURATION.containsMatchIn(text) ||
            text.contains("views", ignoreCase = true) ||
            text.contains("lượt xem", ignoreCase = true) ||
            text.contains(" • ")
    }

    fun isSubmitLabel(text: String): Boolean {
        val n = YouTubeTitleMatcher.normalize(text)
        return n in SUBMIT_LABELS
    }

    fun penalizeUnwantedCompletion(title: String, query: String): Int {
        val titleN = YouTubeTitleMatcher.normalize(title)
        val queryN = YouTubeTitleMatcher.normalize(query)
        val variants = listOf("remix", "karaoke", "cover", "beat")
        var penalty = 0
        for (variant in variants) {
            if (titleN.contains(variant) && !queryN.contains(variant)) penalty += 35
        }
        return penalty
    }
}
