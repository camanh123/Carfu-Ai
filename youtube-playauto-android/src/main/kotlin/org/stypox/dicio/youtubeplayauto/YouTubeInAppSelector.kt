package org.stypox.dicio.youtubeplayauto

sealed class YouTubeSelectOutcome {
    data class DryRun(val query: String, val wouldSeek: Boolean) : YouTubeSelectOutcome()
    data class Armed(val query: String, val youtubePackage: String) : YouTubeSelectOutcome()
    data class Selected(
        val matchedTitle: String,
        val playbackRequested: Boolean,
    ) : YouTubeSelectOutcome()
    data class Unavailable(val reason: String) : YouTubeSelectOutcome()
    data class Failed(val reason: String) : YouTubeSelectOutcome()
}

/**
 * Finds a matching search row inside the YouTube app and clicks it so YouTube can play.
 * Android implementation uses an AccessibilityService in this harness APK only.
 */
interface YouTubeInAppSelector {
    fun isAvailable(): Boolean
    fun selectAndPlay(
        query: String,
        youtubePackage: String,
        mode: YouTubeLaunchMode,
    ): YouTubeSelectOutcome
}

class FakeYouTubeInAppSelector(
    var available: Boolean = true,
    var selectSucceeds: Boolean = true,
    var matchedTitle: String = "Đừng Xa Em Đêm Nay",
) : YouTubeInAppSelector {
    var selectCount: Int = 0
    var lastQuery: String? = null

    override fun isAvailable(): Boolean = available

    override fun selectAndPlay(
        query: String,
        youtubePackage: String,
        mode: YouTubeLaunchMode,
    ): YouTubeSelectOutcome {
        lastQuery = query
        if (mode == YouTubeLaunchMode.DRY_RUN) {
            return YouTubeSelectOutcome.DryRun(query = query, wouldSeek = available && selectSucceeds)
        }
        if (!available) {
            return YouTubeSelectOutcome.Unavailable("in_app_selector_unavailable")
        }
        selectCount += 1
        return if (selectSucceeds) {
            YouTubeSelectOutcome.Selected(
                matchedTitle = matchedTitle,
                playbackRequested = true,
            )
        } else {
            YouTubeSelectOutcome.Failed("no_matching_result")
        }
    }
}
