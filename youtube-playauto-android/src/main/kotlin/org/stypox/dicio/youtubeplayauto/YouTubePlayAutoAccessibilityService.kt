package org.stypox.dicio.youtubeplayauto

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Harness-only service. Not part of production CARFU Voice.
 * Clicks at most one matching YouTube search row per armed job.
 */
class YouTubePlayAutoAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val job = YouTubePlayAutoSelectBus.job ?: return
        val now = System.currentTimeMillis()
        if (now > job.deadlineMs) {
            YouTubePlayAutoSelectBus.lastOutcome =
                YouTubeSelectOutcome.Failed("select_timeout")
            YouTubePlayAutoSelectBus.clear()
            return
        }
        val pkg = event?.packageName?.toString().orEmpty()
        if (pkg.isNotEmpty() && pkg != job.youtubePackage) return
        val root = rootInActiveWindow ?: return
        try {
            val clicked = tryClickMatch(root, job.query)
            if (clicked != null) {
                YouTubePlayAutoSelectBus.lastOutcome = YouTubeSelectOutcome.Selected(
                    matchedTitle = clicked,
                    playbackRequested = true,
                )
                YouTubePlayAutoSelectBus.clear()
            }
        } finally {
            root.recycle()
        }
    }

    private fun tryClickMatch(root: AccessibilityNodeInfo, query: String): String? {
        val found = ArrayList<Pair<AccessibilityNodeInfo, String>>()
        walk(root, found)
        val bestTitle = YouTubeSearchResultPicker.bestSelectable(
            titles = found.map { it.second },
            query = query,
        )?.text ?: return null
        val node = found.firstOrNull { it.second == bestTitle }?.first ?: return null
        val target = clickableAncestor(node) ?: node
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return if (ok) bestTitle else null
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        out: MutableList<Pair<AccessibilityNodeInfo, String>>,
    ) {
        if (YouTubeSearchResultPicker.shouldSkipClass(node.className?.toString())) {
            return
        }
        val text = YouTubeSearchResultPicker.collectText(node)
        if (text.isNotBlank() && !YouTubeSearchResultPicker.shouldSkipText(text)) {
            out += node to text
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, out)
        }
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var hops = 0
        while (cur != null && hops < 8) {
            if (cur.isClickable) return cur
            cur = cur.parent
            hops += 1
        }
        return null
    }

    companion object {
        @Volatile
        var instance: YouTubePlayAutoAccessibilityService? = null
            private set
    }
}
