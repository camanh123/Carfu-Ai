package org.stypox.dicio.youtubeplayauto

/**
 * Isolated YouTube PlayAuto state machine.
 * Submit search exactly once, then wait for a results list, then click one video.
 */
class YouTubePlayAutoSession(
    val query: String,
) {
    val diagnostics = YouTubePlayAutoDiagnostics()
    var submitCount: Int = 0
        private set
    var selectCount: Int = 0
        private set

    fun onUi(facts: YouTubeUiFacts): YouTubePlayAutoAction {
        if (diagnostics.stage == YouTubePlayAutoStage.DONE ||
            diagnostics.stage == YouTubePlayAutoStage.FAILED ||
            diagnostics.stage == YouTubePlayAutoStage.RESULT_SELECTED ||
            diagnostics.stage == YouTubePlayAutoStage.SELECT_REQUESTED
        ) {
            return YouTubePlayAutoAction.None
        }
        if (YouTubeSearchUiClassifier.queryEntered(facts)) {
            diagnostics.queryEntered = true
            if (diagnostics.stage == YouTubePlayAutoStage.SEARCH_OPENED) {
                diagnostics.stage = YouTubePlayAutoStage.QUERY_ENTERED
            }
        }
        val kind = YouTubeSearchUiClassifier.kind(facts.copy(submitted = submitCount > 0))
        return when (diagnostics.stage) {
            YouTubePlayAutoStage.SEARCH_OPENED,
            YouTubePlayAutoStage.QUERY_ENTERED,
            -> enterOrSubmit(kind, facts)
            YouTubePlayAutoStage.SEARCH_SUBMIT_REQUESTED,
            YouTubePlayAutoStage.WAITING_RESULTS,
            -> waitForResults(kind, facts)
            YouTubePlayAutoStage.RESULTS_READY -> selectVideo(facts)
            YouTubePlayAutoStage.SELECT_REQUESTED,
            YouTubePlayAutoStage.RESULT_SELECTED,
            YouTubePlayAutoStage.DONE,
            YouTubePlayAutoStage.FAILED,
            -> YouTubePlayAutoAction.None
        }
    }

    fun markSubmitPerformed(method: YouTubeSearchSubmitMethod) {
        diagnostics.searchSubmitAttempted = true
        diagnostics.searchSubmitMethod = method
        diagnostics.stage = YouTubePlayAutoStage.WAITING_RESULTS
    }

    fun markVideoClicked() {
        onClickResult(success = true)
    }

    fun onClickResult(success: Boolean): YouTubePlayAutoAction {
        diagnostics.actionClickAttempted = true
        diagnostics.actionClickReturned = success
        return if (success) {
            diagnostics.resultSelected = true
            diagnostics.stage = YouTubePlayAutoStage.RESULT_SELECTED
            YouTubePlayAutoAction.None
        } else {
            fail("click_failed")
        }
    }

    fun timeout(): YouTubePlayAutoAction {
        if (diagnostics.stage == YouTubePlayAutoStage.DONE ||
            diagnostics.stage == YouTubePlayAutoStage.RESULT_SELECTED
        ) {
            return YouTubePlayAutoAction.None
        }
        if (diagnostics.stage == YouTubePlayAutoStage.SELECT_REQUESTED) {
            return fail("click_timeout")
        }
        return fail("select_timeout")
    }

    private fun enterOrSubmit(
        kind: YouTubeSearchUiKind,
        facts: YouTubeUiFacts,
    ): YouTubePlayAutoAction {
        if (kind == YouTubeSearchUiKind.RESULTS_LIST) {
            return ready(facts)
        }
        if (submitCount == 0) {
            val method = YouTubeSearchUiClassifier.chooseSubmitMethod(facts)
            submitCount = 1
            diagnostics.searchSubmitAttempted = true
            diagnostics.searchSubmitMethod = method
            diagnostics.stage = YouTubePlayAutoStage.SEARCH_SUBMIT_REQUESTED
            return YouTubePlayAutoAction.SubmitSearch(method)
        }
        diagnostics.stage = YouTubePlayAutoStage.WAITING_RESULTS
        return YouTubePlayAutoAction.Wait
    }

    private fun waitForResults(
        kind: YouTubeSearchUiKind,
        facts: YouTubeUiFacts,
    ): YouTubePlayAutoAction {
        if (kind == YouTubeSearchUiKind.RESULTS_LIST) {
            return ready(facts)
        }
        diagnostics.stage = YouTubePlayAutoStage.WAITING_RESULTS
        return YouTubePlayAutoAction.Wait
    }

    private fun ready(facts: YouTubeUiFacts): YouTubePlayAutoAction {
        diagnostics.resultsPageDetected = true
        diagnostics.stage = YouTubePlayAutoStage.RESULTS_READY
        return selectVideo(facts)
    }

    private fun selectVideo(facts: YouTubeUiFacts): YouTubePlayAutoAction {
        if (selectCount > 0) return YouTubePlayAutoAction.None
        val ranked = YouTubeSearchUiClassifier.candidateResultEntries(facts)
        diagnostics.accessibilityCandidateCount = ranked.size
        diagnostics.candidateVideoCount = ranked.size
        val best = ranked.maxWithOrNull(
            compareBy<YouTubeScoredResult> { it.score }.thenBy { it.title.length },
        )
        if (best == null) {
            return fail("no_matching_video")
        }
        recordBestCandidate(best)
        val resolved = YouTubeVideoClickResolver.resolve(best.node, facts.nodes)
        if (resolved == null) {
            diagnostics.parentDepthUsed = null
            diagnostics.clickableAncestorClass = null
            return fail("no_clickable_ancestor")
        }
        diagnostics.parentDepthUsed = resolved.depthFromTitle
        diagnostics.clickableAncestorClass = resolved.clickableClass
        selectCount = 1
        diagnostics.selectAttemptCount = 1
        diagnostics.stage = YouTubePlayAutoStage.SELECT_REQUESTED
        val titleIndex = if (best.node.index >= 0) best.node.index else facts.nodes.indexOf(best.node)
        return YouTubePlayAutoAction.ClickVideo(
            title = best.title,
            score = best.score,
            titleIndex = titleIndex,
            clickIndex = resolved.nodeIndex,
            titleClass = resolved.titleClass,
            titleClickable = resolved.titleClickable,
            ancestorDepth = resolved.depthFromTitle,
            clickableAncestorClass = resolved.clickableClass,
        )
    }

    private fun recordBestCandidate(best: YouTubeScoredResult) {
        diagnostics.bestMatchedTitle = best.title
        diagnostics.bestNormalizedTitle = YouTubeTitleMatcher.normalize(best.title)
        diagnostics.bestMatchScore = best.score
        diagnostics.bestNodeClass = best.node.className
        diagnostics.bestNodeClickable = best.node.clickable
    }

    private fun fail(reason: String): YouTubePlayAutoAction {
        diagnostics.failureStage = diagnostics.stage
        diagnostics.failureReason = reason
        diagnostics.stage = YouTubePlayAutoStage.FAILED
        return YouTubePlayAutoAction.Fail(reason)
    }
}
