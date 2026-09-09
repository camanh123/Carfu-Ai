package org.stypox.dicio.youtubeplayauto

enum class YouTubePlayAutoStage {
    SEARCH_OPENED,
    QUERY_ENTERED,
    SEARCH_SUBMIT_REQUESTED,
    WAITING_RESULTS,
    RESULTS_READY,
    RESULT_SELECTED,
    DONE,
    FAILED,
}

enum class YouTubeSearchSubmitMethod {
    NONE,
    IME,
    ACCESSIBILITY_BUTTON,
    OTHER,
}

enum class YouTubeSearchUiKind {
    SEARCH_INPUT_OR_SUGGESTIONS,
    RESULTS_LIST,
    UNKNOWN,
}

data class YouTubePlayAutoDiagnostics(
    var queryEntered: Boolean = false,
    var searchSubmitAttempted: Boolean = false,
    var searchSubmitMethod: YouTubeSearchSubmitMethod = YouTubeSearchSubmitMethod.NONE,
    var resultsPageDetected: Boolean = false,
    var candidateVideoCount: Int = 0,
    var bestMatchedTitle: String? = null,
    var bestMatchScore: Int = 0,
    var selectAttemptCount: Int = 0,
    var resultSelected: Boolean = false,
    var stage: YouTubePlayAutoStage = YouTubePlayAutoStage.SEARCH_OPENED,
    var failureStage: YouTubePlayAutoStage? = null,
    var failureReason: String? = null,
) {
    fun format(): String = buildString {
        appendLine("Query entered: ${yesNo(queryEntered)}")
        appendLine("Search submit attempted: ${yesNo(searchSubmitAttempted)}")
        appendLine("Search submit method: $searchSubmitMethod")
        appendLine("Results page detected: ${yesNo(resultsPageDetected)}")
        appendLine("Candidate video count: $candidateVideoCount")
        appendLine("Best matched title: ${bestMatchedTitle ?: "NONE"}")
        appendLine("Best match score: $bestMatchScore")
        appendLine("Select attempt count: $selectAttemptCount")
        appendLine("Result selected: ${yesNo(resultSelected)}")
        appendLine("Failure stage: ${failureStage ?: "none"}")
        appendLine("Failure reason: ${failureReason ?: "none"}")
        appendLine("Stage: $stage")
    }

    private fun yesNo(value: Boolean): String = if (value) "YES" else "NO"
}

sealed class YouTubePlayAutoAction {
    data object Wait : YouTubePlayAutoAction()
    data object None : YouTubePlayAutoAction()
    data class SubmitSearch(val method: YouTubeSearchSubmitMethod) : YouTubePlayAutoAction()
    data class ClickVideo(val title: String, val score: Int) : YouTubePlayAutoAction()
    data class Fail(val reason: String) : YouTubePlayAutoAction()
}
