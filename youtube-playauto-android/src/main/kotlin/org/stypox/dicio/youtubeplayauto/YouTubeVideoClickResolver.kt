package org.stypox.dicio.youtubeplayauto

/**
 * Picks a single ACTION_CLICK target for a YouTube result title.
 * Never uses screen coordinates. Never clicks list containers, chrome, or keyboard.
 */
data class YouTubeResolvedClick(
    val nodeIndex: Int,
    val depthFromTitle: Int,
    val titleClass: String,
    val titleClickable: Boolean,
    val clickableClass: String,
)

object YouTubeVideoClickResolver {
    private val LIST_CONTAINER_MARKERS = listOf(
        "RecyclerView",
        "ListView",
        "GridView",
        "ScrollView",
        "HorizontalScrollView",
        "NestedScrollView",
        "ViewPager",
        "ViewPager2",
    )

    private val KEYBOARD_MARKERS = listOf(
        "inputmethod",
        "Keyboard",
        "latin",
    )

    private val UNSAFE_LABELS = setOf(
        "home",
        "shorts",
        "subscriptions",
        "library",
        "search",
        "all",
        "videos",
        "video",
        "filters",
        "tất cả",
    )

    fun isListContainer(className: String): Boolean =
        LIST_CONTAINER_MARKERS.any { className.contains(it) }

    fun isKeyboardNode(node: YouTubeNodeFact): Boolean {
        val cls = node.className
        val id = node.viewId.lowercase()
        return cls.contains("Key") ||
            KEYBOARD_MARKERS.any { marker ->
                cls.contains(marker) || id.contains(marker.lowercase())
            }
    }

    fun isSafeVideoClickTarget(node: YouTubeNodeFact): Boolean {
        if (isListContainer(node.className)) return false
        if (node.editable) return false
        if (node.className.contains("EditText") || node.className.contains("SearchView")) return false
        if (isKeyboardNode(node)) return false
        val label = YouTubeTitleMatcher.normalize(node.combined)
        if (label in UNSAFE_LABELS) return false
        if (YouTubeSearchUiClassifier.isSubmitLabel(node.combined)) return false
        if (YouTubeSearchResultPicker.shouldSkipText(node.combined) && node.combined.isNotBlank()) {
            return false
        }
        return true
    }

    fun resolve(titleNode: YouTubeNodeFact, nodes: List<YouTubeNodeFact>): YouTubeResolvedClick? {
        val startIdx = indexOf(titleNode, nodes) ?: return null
        val title = nodes[startIdx]
        if (title.clickable && isSafeVideoClickTarget(title)) {
            return YouTubeResolvedClick(
                nodeIndex = stableIndex(title, startIdx),
                depthFromTitle = 0,
                titleClass = title.className,
                titleClickable = true,
                clickableClass = title.className,
            )
        }
        var depth = 0
        var parentId = title.parentIndex
        while (parentId != null) {
            depth += 1
            if (depth > 8) return null
            val parent = findByIndex(nodes, parentId) ?: return null
            if (isListContainer(parent.className)) return null
            if (parent.clickable && isSafeVideoClickTarget(parent)) {
                return YouTubeResolvedClick(
                    nodeIndex = stableIndex(parent, indexOf(parent, nodes) ?: parentId),
                    depthFromTitle = depth,
                    titleClass = title.className,
                    titleClickable = title.clickable,
                    clickableClass = parent.className,
                )
            }
            parentId = parent.parentIndex
        }
        return null
    }

    private fun indexOf(node: YouTubeNodeFact, nodes: List<YouTubeNodeFact>): Int? {
        if (node.index >= 0) {
            val byId = nodes.indexOfFirst { it.index == node.index }
            if (byId >= 0) return byId
        }
        val identity = nodes.indexOf(node)
        if (identity >= 0) return identity
        val byText = nodes.indexOfFirst {
            it.text == node.text &&
                it.className == node.className &&
                it.combined == node.combined
        }
        return byText.takeIf { it >= 0 }
    }

    private fun findByIndex(nodes: List<YouTubeNodeFact>, index: Int): YouTubeNodeFact? {
        nodes.firstOrNull { it.index == index }?.let { return it }
        return nodes.getOrNull(index)
    }

    private fun stableIndex(node: YouTubeNodeFact, fallback: Int): Int =
        if (node.index >= 0) node.index else fallback
}
