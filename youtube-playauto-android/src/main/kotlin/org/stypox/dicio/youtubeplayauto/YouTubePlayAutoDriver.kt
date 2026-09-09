package org.stypox.dicio.youtubeplayauto

import org.stypox.dicio.playauto.adapter.ExecuteOutcome
import org.stypox.dicio.playauto.core.MediaRequest
import org.stypox.dicio.playauto.core.MediaType
import org.stypox.dicio.playauto.core.PlaybackStrategy
import org.stypox.dicio.playauto.provider.MediaProvider

/**
 * YouTube-only PlayAuto driver: search inside YouTube, select a matching row, request play.
 *
 * Isolated from Voice / NLU / Phase 4.4. Failure never closes YouTube and never starts
 * another command session.
 */
class YouTubePlayAutoDriver(
    private val adapter: YouTubeMediaAdapter,
    private val selector: YouTubeInAppSelector,
) : AppPlayAutoDriver {
    override val targetAppId: String = "YouTube"

    var executeCount: Int = 0
        private set

    override fun canHandle(request: PlayAutoRequest): Boolean =
        YouTubeTargetApps.isYouTube(request.targetApp) && request.query.trim().isNotEmpty()

    override fun execute(
        request: PlayAutoRequest,
        mode: YouTubeLaunchMode,
    ): YouTubePlayAutoResult {
        executeCount += 1
        val targetApp = request.targetApp.trim()
        val query = request.query.trim()
        if (!YouTubeTargetApps.isYouTube(targetApp)) {
            return YouTubePlayAutoResult(
                targetApp = targetApp,
                query = query,
                searchOpened = false,
                resultSelected = false,
                playbackRequested = false,
                failure = "unsupported_target_app",
            )
        }
        if (query.isEmpty()) {
            return YouTubePlayAutoResult(
                targetApp = targetApp,
                query = query,
                searchOpened = false,
                resultSelected = false,
                playbackRequested = false,
                failure = "blank_query",
            )
        }

        adapter.launchMode = mode
        adapter.detect()
        val searchBefore = adapter.dispatchCount
        val searchOutcome = adapter.executeExplicit(
            MediaRequest(
                query = query,
                mediaType = MediaType.AUDIO,
                preferredProvider = MediaProvider.YOUTUBE,
            ),
            PlaybackStrategy.SEARCH,
        )
        val searchOpened = searchOutcome is ExecuteOutcome.Accepted
        val searchDispatchCount = adapter.dispatchCount - searchBefore
        if (!searchOpened) {
            val reason = (searchOutcome as? ExecuteOutcome.Failed)?.reason?.name
                ?: "search_failed"
            return YouTubePlayAutoResult(
                targetApp = targetApp,
                query = query,
                searchOpened = false,
                resultSelected = false,
                playbackRequested = false,
                failure = reason,
                youtubeLeftOpen = false,
                searchDispatchCount = searchDispatchCount,
            )
        }

        val pkg = adapter.lastSnapshot.packageName.orEmpty()
        val selected = selector.selectAndPlay(query, pkg, mode)
        return when (selected) {
            is YouTubeSelectOutcome.DryRun -> YouTubePlayAutoResult(
                targetApp = targetApp,
                query = query,
                searchOpened = true,
                resultSelected = false,
                playbackRequested = false,
                failure = if (selected.wouldSeek) null else "selector_would_be_unavailable",
                youtubeLeftOpen = true,
                searchDispatchCount = searchDispatchCount,
            )
            is YouTubeSelectOutcome.Armed -> YouTubePlayAutoResult(
                targetApp = targetApp,
                query = query,
                searchOpened = true,
                resultSelected = false,
                playbackRequested = false,
                failure = null,
                youtubeLeftOpen = true,
                searchDispatchCount = searchDispatchCount,
                selectAttemptCount = 1,
            )
            is YouTubeSelectOutcome.Selected -> YouTubePlayAutoResult(
                targetApp = targetApp,
                query = query,
                searchOpened = true,
                resultSelected = true,
                playbackRequested = selected.playbackRequested,
                matchedTitle = selected.matchedTitle,
                youtubeLeftOpen = true,
                searchDispatchCount = searchDispatchCount,
                selectAttemptCount = 1,
            )
            is YouTubeSelectOutcome.Unavailable -> YouTubePlayAutoResult(
                targetApp = targetApp,
                query = query,
                searchOpened = true,
                resultSelected = false,
                playbackRequested = false,
                failure = selected.reason,
                youtubeLeftOpen = true,
                searchDispatchCount = searchDispatchCount,
            )
            is YouTubeSelectOutcome.Failed -> YouTubePlayAutoResult(
                targetApp = targetApp,
                query = query,
                searchOpened = true,
                resultSelected = false,
                playbackRequested = false,
                failure = selected.reason,
                youtubeLeftOpen = true,
                searchDispatchCount = searchDispatchCount,
                selectAttemptCount = 1,
            )
        }
    }
}
