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
            diagnostics.stage == YouTubePlayAutoStage.RESULT_SELECTED
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
        diagnostics.resultSelected = true
        diagnostics.stage = YouTubePlayAutoStage.DONE
    }

    fun timeout(): YouTubePlayAutoAction {
        if (diagnostics.stage == YouTubePlayAutoStage.DONE ||
            diagnostics.stage == YouTubePlayAutoStage.RESULT_SELECTED
        ) {
            return YouTubePlayAutoAction.None
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
        val titles = YouTubeSearchUiClassifier.candidateVideoTitles(facts)
        diagnostics.candidateVideoCount = titles.size
        val ranked = titles.map { title ->
            val score = YouTubeTitleMatcher.score(title, query) -
                YouTubeSearchUiClassifier.penalizeUnwantedCompletion(title, query)
            title to score
        }.filter { (_, score) -> YouTubeTitleMatcher.isSelectable(score) }
        val best = ranked.maxByOrNull { it.second }
        if (best == null) {
            return fail("no_matching_video")
        }
        diagnostics.bestMatchedTitle = best.first
        diagnostics.bestMatchScore = best.second
        selectCount = 1
        diagnostics.selectAttemptCount = 1
        diagnostics.stage = YouTubePlayAutoStage.RESULT_SELECTED
        return YouTubePlayAutoAction.ClickVideo(best.first, best.second)
    }

    private fun fail(reason: String): YouTubePlayAutoAction {
        diagnostics.failureStage = diagnostics.stage
        diagnostics.failureReason = reason
        diagnostics.stage = YouTubePlayAutoStage.FAILED
        return YouTubePlayAutoAction.Fail(reason)
    }
}
