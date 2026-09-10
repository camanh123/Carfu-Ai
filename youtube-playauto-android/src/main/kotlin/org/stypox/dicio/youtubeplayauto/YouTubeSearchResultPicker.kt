package org.stypox.dicio.youtubeplayauto

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Pure tree walk used by the harness AccessibilityService. Unit-tested without Android UI.
 */
object YouTubeSearchResultPicker {
    data class Candidate(
        val text: String,
        val score: Int,
        val skip: Boolean,
    )

    fun shouldSkipClass(className: String?): Boolean {
        val name = className.orEmpty()
        return name.contains("EditText") ||
            name.contains("SearchView") ||
            name.contains("AutoComplete")
    }

    fun shouldSkipText(text: String): Boolean {
        val n = text.trim().lowercase()
        if (n.isEmpty()) return true
        return n == "search" ||
            n == "search youtube" ||
            n == "shorts" ||
            n == "home" ||
            n == "subscriptions" ||
            n == "library" ||
            n == "filters" ||
            n.startsWith("search for")
    }

    fun bestSelectable(titles: List<String>, query: String): Candidate? {
        val ranked = titles.map { title ->
            Candidate(
                text = title,
                score = YouTubeTitleMatcher.score(title, query),
                skip = shouldSkipText(title),
            )
        }.filter { !it.skip && YouTubeTitleMatcher.isSelectable(it.score) }
        return ranked.maxByOrNull { it.score }
    }

    fun collectText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val parts = mutableListOf<String>()
        node.text?.toString()?.let { if (it.isNotBlank()) parts += it }
        node.contentDescription?.toString()?.let { if (it.isNotBlank()) parts += it }
        return parts.joinToString(" ")
    }
}
