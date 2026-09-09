package org.stypox.dicio.youtubeplayauto

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Harness-only. Submits YouTube search once, waits for the results list, then clicks
 * one matching video. Never clicks autocomplete suggestions.
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
            val fail = job.session.timeout()
            applyFail(fail)
            return
        }
        val pkg = event?.packageName?.toString().orEmpty()
        if (pkg.isNotEmpty() && pkg != job.youtubePackage) return
        val root = rootInActiveWindow ?: return
        try {
            handle(job, root)
        } finally {
            root.recycle()
        }
    }

    private fun handle(job: YouTubeSelectJob, root: AccessibilityNodeInfo) {
        val facts = YouTubeUiFacts(
            query = job.query,
            submitted = job.session.submitCount > 0,
            nodes = collectFacts(root),
        )
        when (val action = job.session.onUi(facts)) {
            is YouTubePlayAutoAction.SubmitSearch -> {
                performSubmit(root, facts, action.method)
                job.session.markSubmitPerformed(action.method)
                YouTubePlayAutoSelectBus.lastDiagnostics = job.session.diagnostics
            }
            is YouTubePlayAutoAction.ClickVideo -> {
                val ok = clickTitle(root, action.title)
                if (ok) {
                    job.session.markVideoClicked()
                    YouTubePlayAutoSelectBus.lastOutcome = YouTubeSelectOutcome.Selected(
                        matchedTitle = action.title,
                        playbackRequested = true,
                    )
                    YouTubePlayAutoSelectBus.lastDiagnostics = job.session.diagnostics
                    YouTubePlayAutoSelectBus.clear()
                }
            }
            is YouTubePlayAutoAction.Fail -> applyFail(action)
            YouTubePlayAutoAction.Wait,
            YouTubePlayAutoAction.None,
            -> {
                YouTubePlayAutoSelectBus.lastDiagnostics = job.session.diagnostics
            }
        }
    }

    private fun applyFail(action: YouTubePlayAutoAction) {
        val reason = (action as? YouTubePlayAutoAction.Fail)?.reason ?: "failed"
        YouTubePlayAutoSelectBus.lastOutcome = YouTubeSelectOutcome.Failed(reason)
        YouTubePlayAutoSelectBus.lastDiagnostics = YouTubePlayAutoSelectBus.diagnostics()
        YouTubePlayAutoSelectBus.clear()
    }

    private fun performSubmit(
        root: AccessibilityNodeInfo,
        facts: YouTubeUiFacts,
        method: YouTubeSearchSubmitMethod,
    ) {
        when (method) {
            YouTubeSearchSubmitMethod.IME -> {
                if (!imeEnter(root, facts)) clickSubmitControl(root, facts)
            }
            YouTubeSearchSubmitMethod.ACCESSIBILITY_BUTTON -> {
                if (!clickSubmitControl(root, facts)) imeEnter(root, facts)
            }
            YouTubeSearchSubmitMethod.OTHER -> {
                imeEnter(root, facts) || clickSubmitControl(root, facts)
            }
            YouTubeSearchSubmitMethod.NONE -> Unit
        }
    }

    private fun imeEnter(root: AccessibilityNodeInfo, facts: YouTubeUiFacts): Boolean {
        val field = YouTubeSearchUiClassifier.searchField(facts) ?: return false
        val node = findNode(root) { matches(it, field) } ?: return false
        if (Build.VERSION.SDK_INT >= 30) {
            if (node.performAction(android.R.id.accessibilityActionImeEnter)) return true
        }
        val imeKey = YouTubeSearchUiClassifier.imeSearchKey(facts)
        if (imeKey != null) {
            val keyNode = findNode(root) { matches(it, imeKey) }
            if (keyNode != null && keyNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        return false
    }

    private fun clickSubmitControl(root: AccessibilityNodeInfo, facts: YouTubeUiFacts): Boolean {
        val button = YouTubeSearchUiClassifier.submitButton(facts) ?: return false
        val node = findNode(root) { matches(it, button) } ?: return false
        val target = if (node.isClickable) node else clickableAncestor(node) ?: node
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun clickTitle(root: AccessibilityNodeInfo, title: String): Boolean {
        val node = findNode(root) { YouTubeSearchResultPicker.collectText(it) == title }
            ?: return false
        val target = clickableAncestor(node) ?: node
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun collectFacts(root: AccessibilityNodeInfo): List<YouTubeNodeFact> {
        val out = ArrayList<YouTubeNodeFact>()
        walk(root) { node ->
            out += YouTubeNodeFact(
                className = node.className?.toString().orEmpty(),
                text = node.text?.toString().orEmpty(),
                contentDescription = node.contentDescription?.toString().orEmpty(),
                viewId = node.viewIdResourceName.orEmpty(),
                clickable = node.isClickable,
                focused = node.isFocused,
                editable = node.isEditable,
                hasImeEnterAction = hasImeEnter(node),
            )
        }
        return out
    }

    private fun findNode(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        walk(root) { node ->
            if (found == null && predicate(node)) found = node
        }
        return found
    }

    private fun walk(node: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Unit) {
        visit(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, visit)
        }
    }

    private fun matches(node: AccessibilityNodeInfo, fact: YouTubeNodeFact): Boolean {
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val id = node.viewIdResourceName.orEmpty()
        return text == fact.text && desc == fact.contentDescription && id == fact.viewId
    }

    private fun hasImeEnter(node: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        return node.actionList.any { it.id == android.R.id.accessibilityActionImeEnter }
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
