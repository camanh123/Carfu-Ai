package org.stypox.dicio.youtubeplayauto

import kotlinx.coroutines.runBlocking
import org.stypox.dicio.playauto.adapter.ExecuteOutcome
import org.stypox.dicio.playauto.core.MediaRequest
import org.stypox.dicio.playauto.core.MediaType
import org.stypox.dicio.playauto.core.PlaybackStrategy
import org.stypox.dicio.playauto.provider.MediaProvider
import java.util.concurrent.atomic.AtomicBoolean

/**
 * YouTube PlayAuto driver: resolve an exact watch target, then launch it once.
 *
 * Primary path is DIRECT_TARGET (DEEP_LINK watch URL). When a
 * [YouTubeResolverClient] is supplied, PlayAuto talks to the frozen CARFU
 * resolver REST contract and then uses the existing DIRECT_TARGET launcher.
 * Accessibility is not used unless [YouTubePlayAutoOptions.accessibilityFallbackEnabled]
 * is true **and** no resolver client is configured.
 *
 * Isolated from Voice / NLU / Phase 4.4. No Cast SDK. No media keys.
 */
class YouTubePlayAutoDriver(
    private val adapter: YouTubeMediaAdapter,
    private val selector: YouTubeInAppSelector,
    private val resolver: YouTubeContentResolver = NoOpYouTubeContentResolver,
    private val options: YouTubePlayAutoOptions = YouTubePlayAutoOptions(),
    private val resolverClient: YouTubeResolverClient? = null,
) : AppPlayAutoDriver {
    override val targetAppId: String = "YouTube"

    var executeCount: Int = 0
        private set

    private val requestClaimed = AtomicBoolean(false)
    @Volatile private var lastResult: YouTubePlayAutoResult? = null

    override fun canHandle(request: PlayAutoRequest): Boolean =
        YouTubeTargetApps.isYouTube(request.targetApp) && request.query.trim().isNotEmpty()

    fun resolveOnly(query: String): YouTubeContentResolution {
        val parsed = ParsedYouTubeContentResolver.resolveQuery(query)
        if (parsed is YouTubeContentResolution.Resolved) return parsed
        val client = resolverClient ?: return resolver.resolveQuery(query)
        return when (val outcome = runBlocking { client.resolve(query, options.resolverLang, options.resolverRegion) }) {
            is YouTubeResolveResult.Resolved -> YouTubeContentResolution.Resolved(
                ResolvedYouTubeTarget(
                    videoId = outcome.videoId,
                    canonicalUri = outcome.watchUrl,
                    title = outcome.title,
                    source = "carfu_resolver_service",
                ),
            )
            else -> YouTubeContentResolution.Unresolved(query.trim(), outcome.statusName())
        }
    }

    override fun execute(
        request: PlayAutoRequest,
        mode: YouTubeLaunchMode,
    ): YouTubePlayAutoResult {
        executeCount += 1
        val targetApp = request.targetApp.trim()
        val query = request.query.trim()
        if (!YouTubeTargetApps.isYouTube(targetApp)) {
            return finish(reject(targetApp, query, "unsupported_target_app"))
        }
        if (query.isEmpty()) {
            return finish(reject(targetApp, query, "blank_query"))
        }
        if (!requestClaimed.compareAndSet(false, true)) {
            return lastResult ?: reject(targetApp, query, "duplicate_request")
        }

        adapter.launchMode = mode
        adapter.detect()
        val client = resolverClient
        if (client != null) {
            return finish(executeResolverJack(targetApp, query, mode, client))
        }
        val resolution = resolver.resolveQuery(query)
        return finish(
            when (resolution) {
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
            },
        )
    }

    fun openExact(
        target: ResolvedYouTubeTarget,
        query: String,
        mode: YouTubeLaunchMode,
    ): YouTubePlayAutoResult {
        executeCount += 1
        if (!requestClaimed.compareAndSet(false, true)) {
            return lastResult ?: reject("YouTube", query.trim(), "duplicate_request")
        }
        adapter.launchMode = mode
        adapter.detect()
        return finish(launchDirect("YouTube", query.trim(), target, mode))
    }

    private fun executeResolverJack(
        targetApp: String,
        query: String,
        mode: YouTubeLaunchMode,
        client: YouTubeResolverClient,
    ): YouTubePlayAutoResult {
        val parsed = ParsedYouTubeContentResolver.resolveQuery(query)
        if (parsed is YouTubeContentResolution.Resolved) {
            return launchDirect(
                targetApp = targetApp,
                query = query,
                target = parsed.target,
                mode = mode,
                path = "RESOLVER_DIRECT_TARGET",
                diagnostics = ResolverJackDiagnostics(
                    baseUrlConfigured = YouTubeResolverEndpoint.isConfigured(
                        // parse-first does not require the service
                        "",
                    ),
                    requestAttempted = false,
                    status = "PARSED_LOCAL",
                ),
            )
        }
        val outcome = runBlocking {
            client.resolve(query, options.resolverLang, options.resolverRegion)
        }
        val meta = outcome.meta()
        val diagnostics = ResolverJackDiagnostics(
            baseUrlConfigured = meta.baseUrlConfigured,
            requestAttempted = meta.requestAttempted,
            status = outcome.statusName(),
            httpStatus = meta.httpStatus,
            cache = (outcome as? YouTubeResolveResult.Resolved)?.cache,
            latencyMs = meta.latencyMs,
            https = meta.usedHttps,
            cleartext = meta.usedCleartextHttp,
            httpsNote = meta.httpsUnavailableNote,
            channelTitle = (outcome as? YouTubeResolveResult.Resolved)?.channelTitle,
        )
        return when (outcome) {
            is YouTubeResolveResult.Resolved -> launchDirect(
                targetApp = targetApp,
                query = query,
                target = ResolvedYouTubeTarget(
                    videoId = outcome.videoId,
                    canonicalUri = outcome.watchUrl,
                    title = outcome.title,
                    source = "carfu_resolver_service",
                ),
                mode = mode,
                path = "RESOLVER_DIRECT_TARGET",
                diagnostics = diagnostics,
            )
            is YouTubeResolveResult.NoResults -> reject(
                targetApp, query, "NO_RESULTS",
                resolutionMethod = "carfu_resolver_service",
                path = "RESOLVER_DIRECT_TARGET",
                diagnostics = diagnostics,
            )
            is YouTubeResolveResult.NetworkUnavailable -> reject(
                targetApp, query, "NETWORK_UNAVAILABLE",
                resolutionMethod = "carfu_resolver_service",
                path = "RESOLVER_DIRECT_TARGET",
                diagnostics = diagnostics,
            )
            is YouTubeResolveResult.ResolverUnavailable -> reject(
                targetApp, query, "RESOLVER_UNAVAILABLE",
                resolutionMethod = "carfu_resolver_service",
                path = "RESOLVER_DIRECT_TARGET",
                diagnostics = diagnostics,
            )
            is YouTubeResolveResult.QuotaExceeded -> reject(
                targetApp, query, "QUOTA_EXCEEDED",
                resolutionMethod = "carfu_resolver_service",
                path = "RESOLVER_DIRECT_TARGET",
                diagnostics = diagnostics,
            )
            is YouTubeResolveResult.InvalidResponse -> reject(
                targetApp, query, "INVALID_RESPONSE",
                resolutionMethod = "carfu_resolver_service",
                path = "RESOLVER_DIRECT_TARGET",
                diagnostics = diagnostics,
            )
            is YouTubeResolveResult.Timeout -> reject(
                targetApp, query, "TIMEOUT",
                resolutionMethod = "carfu_resolver_service",
                path = "RESOLVER_DIRECT_TARGET",
                diagnostics = diagnostics,
            )
        }
    }

    private fun launchDirect(
        targetApp: String,
        query: String,
        target: ResolvedYouTubeTarget,
        mode: YouTubeLaunchMode,
        path: String = "DIRECT_TARGET",
        diagnostics: ResolverJackDiagnostics = ResolverJackDiagnostics(),
    ): YouTubePlayAutoResult {
        val id = YouTubeVideoIdParser.parse(target.videoId)
        if (id == null) {
            return reject(
                targetApp = targetApp,
                query = query,
                failure = "invalid_video_id",
                resolutionMethod = target.source,
                path = path,
                diagnostics = diagnostics,
            )
        }
        val official = YouTubeVideoIdParser.canonicalWatchUri(id)
        val preserved = when {
            YouTubeVideoIdParser.parse(target.canonicalUri) == id &&
                target.canonicalUri.startsWith(YouTubePublicLaunchAudit.WATCH_HOST_PATH) ->
                target.canonicalUri
            else -> official
        }
        val safeTarget = target.copy(videoId = id, canonicalUri = preserved)
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
            targetUri = preserved,
            resolverSuccess = true,
            launchAttempted = launched == 1 || accepted,
            launchResult = launchResult,
            accessibilityFallbackUsed = false,
            castApisUsed = false,
            mediaKeysSent = false,
            path = path,
            resolverBaseUrlConfigured = diagnostics.baseUrlConfigured,
            resolverRequestAttempted = diagnostics.requestAttempted,
            resolverStatus = diagnostics.status ?: "RESOLVED",
            resolverHttpStatus = diagnostics.httpStatus,
            resolvedChannelTitle = diagnostics.channelTitle,
            resolverCache = diagnostics.cache,
            resolverLatencyMs = diagnostics.latencyMs,
            resolverHttps = diagnostics.https,
            resolverCleartextHttp = diagnostics.cleartext,
            httpsUnavailableNote = diagnostics.httpsNote,
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
        path: String = "DIRECT_TARGET",
        diagnostics: ResolverJackDiagnostics = ResolverJackDiagnostics(),
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
        path = path,
        resolverBaseUrlConfigured = diagnostics.baseUrlConfigured,
        resolverRequestAttempted = diagnostics.requestAttempted,
        resolverStatus = diagnostics.status ?: failure,
        resolverHttpStatus = diagnostics.httpStatus,
        resolvedChannelTitle = diagnostics.channelTitle,
        resolverCache = diagnostics.cache,
        resolverLatencyMs = diagnostics.latencyMs,
        resolverHttps = diagnostics.https,
        resolverCleartextHttp = diagnostics.cleartext,
        httpsUnavailableNote = diagnostics.httpsNote,
    )

    private fun finish(result: YouTubePlayAutoResult): YouTubePlayAutoResult {
        lastResult = result
        return result
    }
}

internal data class ResolverJackDiagnostics(
    val baseUrlConfigured: Boolean = false,
    val requestAttempted: Boolean = false,
    val status: String? = null,
    val httpStatus: Int? = null,
    val cache: String? = null,
    val latencyMs: Long? = null,
    val https: Boolean? = null,
    val cleartext: Boolean = false,
    val httpsNote: String? = null,
    val channelTitle: String? = null,
)
