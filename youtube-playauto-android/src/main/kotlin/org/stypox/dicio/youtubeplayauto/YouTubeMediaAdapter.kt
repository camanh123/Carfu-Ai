package org.stypox.dicio.youtubeplayauto

import org.stypox.dicio.playauto.adapter.ExecuteOutcome
import org.stypox.dicio.playauto.adapter.MediaAdapter
import org.stypox.dicio.playauto.adapter.ResolveOutcome
import org.stypox.dicio.playauto.core.MediaRequest
import org.stypox.dicio.playauto.core.MediaTarget
import org.stypox.dicio.playauto.core.MediaType
import org.stypox.dicio.playauto.core.PlaybackCapability
import org.stypox.dicio.playauto.core.PlaybackFailureReason
import org.stypox.dicio.playauto.core.PlaybackStrategy
import org.stypox.dicio.playauto.provider.MediaProvider

/**
 * Android YouTube [MediaAdapter] for Phase 4.7.
 *
 * Consumes frozen PlayAuto Core contracts. Does not modify the engine.
 *
 * Engine-advertised capabilities are SEARCH and OPEN_APP when resolvable.
 * DEEP_LINK is implemented as a harness/strategy path that requires an injected
 * video id. DIRECT_PLAY is never advertised.
 */
class YouTubeMediaAdapter(
    private val runtime: YouTubeRuntime,
    private val contentResolver: YouTubeContentResolver = NoOpYouTubeContentResolver,
    var launchMode: YouTubeLaunchMode = YouTubeLaunchMode.DRY_RUN,
) : MediaAdapter {
    private val detector = YouTubeCapabilityDetector(runtime)

    var lastSnapshot: YouTubeCapabilitySnapshot = detector.detect()
        private set
    var lastDispatch: YouTubeDispatchOutcome? = null
        private set
    var lastSpec: YouTubeLaunchSpec? = null
        private set
    var lastRequest: MediaRequest? = null
        private set
    var lastTarget: MediaTarget? = null
        private set
    var lastStrategy: PlaybackStrategy? = null
        private set
    var executionCount: Int = 0
        private set
    var resolveCount: Int = 0
        private set
    var dispatchCount: Int = 0
        private set

    override val provider: MediaProvider = MediaProvider.YOUTUBE

    override val available: Boolean
        get() = lastSnapshot.installed && lastSnapshot.engineCapabilities.isNotEmpty()

    override val capabilities: Set<PlaybackCapability>
        get() = lastSnapshot.engineCapabilities

    fun detect(): YouTubeCapabilitySnapshot {
        lastSnapshot = detector.detect()
        return lastSnapshot
    }

    override fun canHandle(request: MediaRequest): Boolean {
        if (!available) return false
        return request.mediaType == MediaType.AUDIO ||
            request.mediaType == MediaType.VIDEO ||
            request.mediaType == MediaType.UNKNOWN
    }

    override fun resolve(request: MediaRequest): ResolveOutcome {
        resolveCount += 1
        lastRequest = request
        val query = request.query.trim()
        if (query.isEmpty()) {
            return ResolveOutcome.Failed(PlaybackFailureReason.INVALID_REQUEST)
        }
        val resolved = when (val content = contentResolver.resolveQuery(query)) {
            is YouTubeContentResolution.Resolved -> content.target
            is YouTubeContentResolution.Unresolved -> null
        }
        val target = YouTubeTargetDescriptor.mediaTarget(query, request.mediaType, resolved)
        lastTarget = target
        return ResolveOutcome.Ok(target)
    }

    override fun execute(target: MediaTarget, strategy: PlaybackStrategy): ExecuteOutcome {
        executionCount += 1
        lastTarget = target
        lastStrategy = strategy
        lastDispatch = null
        lastSpec = null
        val pkg = lastSnapshot.packageName ?: runtime.installedPackage()?.packageName
        val resolved = YouTubeTargetDescriptor.parseVideo(target.descriptor)
        val input = YouTubeStrategyInput(
            packageName = pkg,
            query = target.query,
            resolved = resolved,
        )
        val built = strategyFor(strategy).build(input)
        val spec = when (built) {
            is YouTubeStrategyBuild.Failed -> {
                return ExecuteOutcome.Failed(built.reason)
            }
            is YouTubeStrategyBuild.Ok -> built.spec
        }
        lastSpec = spec
        if (!runtime.canResolve(spec)) {
            lastDispatch = YouTubeDispatchOutcome.Failed("activity_unresolved")
            return ExecuteOutcome.Failed(PlaybackFailureReason.EXECUTION_FAILED)
        }
        val outcome = runtime.dispatch(spec, launchMode)
        lastDispatch = outcome
        return when (outcome) {
            is YouTubeDispatchOutcome.DryRun -> {
                dispatchCount += 1
                ExecuteOutcome.Accepted(strategy)
            }
            is YouTubeDispatchOutcome.Dispatched -> {
                dispatchCount += 1
                ExecuteOutcome.Accepted(strategy)
            }
            is YouTubeDispatchOutcome.Failed ->
                ExecuteOutcome.Failed(PlaybackFailureReason.EXECUTION_FAILED)
        }
    }

    fun executeExplicit(
        request: MediaRequest,
        strategy: PlaybackStrategy,
        injectedTarget: ResolvedYouTubeTarget? = null,
    ): ExecuteOutcome {
        detect()
        if (request.query.trim().isEmpty() && strategy == PlaybackStrategy.SEARCH) {
            return ExecuteOutcome.Failed(PlaybackFailureReason.INVALID_REQUEST)
        }
        if (!lastSnapshot.installed && strategy != PlaybackStrategy.DIRECT_PLAY) {
            return ExecuteOutcome.Failed(PlaybackFailureReason.PROVIDER_UNAVAILABLE)
        }
        val resolvedContent = injectedTarget ?: when (val content = contentResolver.resolveQuery(request.query)) {
            is YouTubeContentResolution.Resolved -> content.target
            is YouTubeContentResolution.Unresolved -> null
        }
        val target = YouTubeTargetDescriptor.mediaTarget(
            query = request.query.trim(),
            mediaType = request.mediaType,
            resolved = resolvedContent,
        )
        return execute(target, strategy)
    }

    private fun strategyFor(strategy: PlaybackStrategy): YouTubeLaunchStrategy = when (strategy) {
        PlaybackStrategy.OPEN_APP -> YouTubeOpenAppStrategy
        PlaybackStrategy.SEARCH -> YouTubeSearchStrategy
        PlaybackStrategy.DEEP_LINK -> YouTubeDeepLinkStrategy
        PlaybackStrategy.DIRECT_PLAY -> YouTubeDirectPlayStrategy
    }
}
