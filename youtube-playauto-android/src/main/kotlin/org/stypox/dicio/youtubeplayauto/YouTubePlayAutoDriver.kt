package org.stypox.dicio.youtubeplayauto

import org.stypox.dicio.playauto.adapter.ExecuteOutcome
import org.stypox.dicio.playauto.core.MediaRequest
import org.stypox.dicio.playauto.core.MediaType
import org.stypox.dicio.playauto.core.PlaybackStrategy
import org.stypox.dicio.playauto.provider.MediaProvider

/**
 * YouTube PlayAuto driver: resolve an exact watch target, then launch it once.
 *
 * Primary path is DIRECT_TARGET (DEEP_LINK watch URL). Accessibility is not used
 * unless [YouTubePlayAutoOptions.accessibilityFallbackEnabled] is true.
 *
 * Isolated from Voice / NLU / Phase 4.4. No Cast SDK. No media keys.
 */
class YouTubePlayAutoDriver(
    private val adapter: YouTubeMediaAdapter,
    private val selector: YouTubeInAppSelector,
    private val resolver: YouTubeContentResolver = NoOpYouTubeContentResolver,
    private val options: YouTubePlayAutoOptions = YouTubePlayAutoOptions(),
) : AppPlayAutoDriver {
    override val targetAppId: String = "YouTube"

    var executeCount: Int = 0
        private set

    override fun canHandle(request: PlayAutoRequest): Boolean =
        YouTubeTargetApps.isYouTube(request.targetApp) && request.query.trim().isNotEmpty()

    fun resolveOnly(query: String): YouTubeContentResolution = resolver.resolveQuery(query)

    override fun execute(
        request: PlayAutoRequest,
        mode: YouTubeLaunchMode,
    ): YouTubePlayAutoResult {
        executeCount += 1
        val targetApp = request.targetApp.trim()
        val query = request.query.trim()
        if (!YouTubeTargetApps.isYouTube(targetApp)) {
            return reject(targetApp, query, "unsupported_target_app")
        }
        if (query.isEmpty()) {
            return reject(targetApp, query, "blank_query")
        }

        adapter.launchMode = mode
        adapter.detect()
        val resolution = resolver.resolveQuery(query)
        return when (resolution) {
            is YouTubeContentResolution.Resolved ->
                launchDirect(targetApp, query, resolution.target, mode)
            is YouTubeContentResolution.Unresolved -> {
                if (options.accessibilityFallbackEnabled) {
                    accessibilityFallback(targetApp, query, mode, resolution.reason)
                } else {
                    reject(
                        targetApp = targetApp,
                        query = query,
                        failure = resolution.reason,
                        resolutionMethod = "unresolved",
                    )
                }
            }
        }
    }

    fun openExact(
        target: ResolvedYouTubeTarget,
        query: String,
        mode: YouTubeLaunchMode,
    ): YouTubePlayAutoResult {
        executeCount += 1
        adapter.launchMode = mode
        adapter.detect()
        return launchDirect("YouTube", query.trim(), target, mode)
    }

    private fun launchDirect(
        targetApp: String,
        query: String,
        target: ResolvedYouTubeTarget,
        mode: YouTubeLaunchMode,
    ): YouTubePlayAutoResult {
        val id = YouTubeVideoIdParser.parse(target.videoId)
        if (id == null) {
            return reject(
                targetApp = targetApp,
                query = query,
                failure = "invalid_video_id",
                resolutionMethod = target.source,
            )
        }
        val canonical = YouTubeVideoIdParser.canonicalWatchUri(id)
        val safeTarget = target.copy(videoId = id, canonicalUri = canonical)
        val dispatchBefore = adapter.dispatchCount
        val outcome = adapter.executeExplicit(
            MediaRequest(
                query = query,
                mediaType = MediaType.AUDIO,
                preferredProvider = MediaProvider.YOUTUBE,
            ),
            PlaybackStrategy.DEEP_LINK,
            safeTarget,
        )
        val launched = adapter.dispatchCount - dispatchBefore
        val accepted = outcome is ExecuteOutcome.Accepted
        val launchResult = when (val dispatch = adapter.lastDispatch) {
            is YouTubeDispatchOutcome.DryRun -> "DRY_RUN ${dispatch.provenance}"
            is YouTubeDispatchOutcome.Dispatched -> "DISPATCHED ${dispatch.provenance}"
            is YouTubeDispatchOutcome.Failed -> "FAILED ${dispatch.detail}"
            null -> if (accepted) "ACCEPTED" else "FAILED"
        }
        return YouTubePlayAutoResult(
            targetApp = targetApp,
            query = query,
            searchOpened = false,
            resultSelected = false,
            playbackRequested = accepted && mode == YouTubeLaunchMode.DEVICE_TEST,
            matchedTitle = safeTarget.title,
            failure = if (accepted) null else "direct_target_launch_failed",
            youtubeLeftOpen = accepted,
            searchDispatchCount = 0,
            selectAttemptCount = 0,
            resolvedVideoId = id,
            resolvedTitle = safeTarget.title,
            resolutionMethod = safeTarget.source,
            targetUri = canonical,
            resolverSuccess = true,
            launchAttempted = launched == 1 || accepted,
            launchResult = launchResult,
            accessibilityFallbackUsed = false,
            castApisUsed = false,
            mediaKeysSent = false,
            path = "DIRECT_TARGET",
        )
    }

    private fun accessibilityFallback(
        targetApp: String,
        query: String,
        mode: YouTubeLaunchMode,
        resolveReason: String,
    ): YouTubePlayAutoResult {
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
                failure = "$reason; resolver=$resolveReason",
                youtubeLeftOpen = false,
                searchDispatchCount = searchDispatchCount,
                resolutionMethod = "unresolved",
                resolverSuccess = false,
                launchAttempted = false,
                launchResult = "SEARCH_NOT_OPENED",
                accessibilityFallbackUsed = true,
                path = "ACCESSIBILITY_FALLBACK",
            )
        }
        val pkg = adapter.lastSnapshot.packageName.orEmpty()
        val selected = selector.selectAndPlay(query, pkg, mode)
        val base = fallbackFromSelect(targetApp, query, selected, searchDispatchCount, resolveReason)
        return base.copy(accessibilityFallbackUsed = true, path = "ACCESSIBILITY_FALLBACK")
    }

    private fun fallbackFromSelect(
        targetApp: String,
        query: String,
        selected: YouTubeSelectOutcome,
        searchDispatchCount: Int,
        resolveReason: String,
    ): YouTubePlayAutoResult = when (selected) {
        is YouTubeSelectOutcome.DryRun -> YouTubePlayAutoResult(
            targetApp = targetApp,
            query = query,
            searchOpened = true,
            resultSelected = false,
            playbackRequested = false,
            failure = if (selected.wouldSeek) resolveReason else "selector_would_be_unavailable",
            youtubeLeftOpen = true,
            searchDispatchCount = searchDispatchCount,
            resolutionMethod = "unresolved",
            resolverSuccess = false,
            launchAttempted = true,
            launchResult = "SEARCH_DRY_RUN",
            accessibilityFallbackUsed = true,
            path = "ACCESSIBILITY_FALLBACK",
        )
        is YouTubeSelectOutcome.Armed -> YouTubePlayAutoResult(
            targetApp = targetApp,
            query = query,
            searchOpened = true,
            resultSelected = false,
            playbackRequested = false,
            failure = resolveReason,
            youtubeLeftOpen = true,
            searchDispatchCount = searchDispatchCount,
            selectAttemptCount = 1,
            resolutionMethod = "unresolved",
            resolverSuccess = false,
            launchAttempted = true,
            launchResult = "SEARCH_ARMED_A11Y",
            accessibilityFallbackUsed = true,
            path = "ACCESSIBILITY_FALLBACK",
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
            resolvedTitle = selected.matchedTitle,
            resolutionMethod = "accessibility_fallback",
            resolverSuccess = false,
            launchAttempted = true,
            launchResult = "A11Y_SELECTED",
            accessibilityFallbackUsed = true,
            path = "ACCESSIBILITY_FALLBACK",
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
            resolutionMethod = "unresolved",
            resolverSuccess = false,
            launchAttempted = true,
            launchResult = "A11Y_UNAVAILABLE",
            accessibilityFallbackUsed = true,
            path = "ACCESSIBILITY_FALLBACK",
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
            resolutionMethod = "unresolved",
            resolverSuccess = false,
            launchAttempted = true,
            launchResult = "A11Y_FAILED",
            accessibilityFallbackUsed = true,
            path = "ACCESSIBILITY_FALLBACK",
        )
    }

    private fun reject(
        targetApp: String,
        query: String,
        failure: String,
        resolutionMethod: String? = null,
    ): YouTubePlayAutoResult = YouTubePlayAutoResult(
        targetApp = targetApp,
        query = query,
        searchOpened = false,
        resultSelected = false,
        playbackRequested = false,
        failure = failure,
        youtubeLeftOpen = false,
        resolutionMethod = resolutionMethod,
        resolverSuccess = false,
        launchAttempted = false,
        launchResult = "NOT_LAUNCHED",
        accessibilityFallbackUsed = false,
        path = "DIRECT_TARGET",
    )
}
