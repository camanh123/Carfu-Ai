package org.stypox.dicio.smarttubeplayauto

import kotlinx.coroutines.runBlocking
import org.stypox.dicio.youtubeplayauto.PlayAutoRequest
import org.stypox.dicio.youtubeplayauto.YouTubeLaunchMode
import org.stypox.dicio.youtubeplayauto.YouTubeResolveResult
import org.stypox.dicio.youtubeplayauto.YouTubeResolverClient
import org.stypox.dicio.youtubeplayauto.YouTubeResolverMeta
import org.stypox.dicio.youtubeplayauto.YouTubeVideoIdParser
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Standalone SmartTube PlayAuto driver.
 *
 * Reuses the frozen CARFU resolver contract (query → videoId/title/watchUrl).
 * Does not modify YouTube PlayAuto, the resolver, or production Voice.
 *
 * Default [execute] resolves only. Source does not prove a SmartTube Intent form,
 * so this driver never guesses a launch. Tests / harness inject a single
 * [SmartTubePlayAutoOptions.launchForm] for one explicit attempt.
 *
 * After the first external launch attempt: no fallback, never YouTube, never a
 * second media app.
 */
class SmartTubePlayAutoDriver(
    private val resolverClient: YouTubeResolverClient,
    private val launcher: SmartTubeLauncher,
    private val options: SmartTubePlayAutoOptions = SmartTubePlayAutoOptions(),
) {
    val targetAppId: String = "SmartTube"

    var executeCount: Int = 0
        private set

    private val requestClaimed = AtomicBoolean(false)
    @Volatile private var lastResult: SmartTubePlayAutoResult? = null

    fun canHandle(request: PlayAutoRequest): Boolean =
        SmartTubeTargetApps.isSmartTube(request.targetApp) && request.query.trim().isNotEmpty()

    fun execute(
        request: PlayAutoRequest,
        mode: YouTubeLaunchMode,
    ): SmartTubePlayAutoResult {
        executeCount += 1
        val targetApp = request.targetApp.trim()
        val query = request.query.trim()
        if (!SmartTubeTargetApps.isSmartTube(targetApp)) {
            return finish(reject(targetApp, query, "unsupported_target_app", path = "REJECT"))
        }
        if (query.isEmpty()) {
            return finish(reject(targetApp, query, "blank_query", path = "REJECT"))
        }
        if (!requestClaimed.compareAndSet(false, true)) {
            return lastResult ?: reject(targetApp, query, "duplicate_request", path = "REJECT")
        }

        val installed = launcher.installedPackages()
        val installedNames = installed.map { it.packageName }
        val pinDecision = SmartTubeTargetSelection.pin(installed, options.selectedPackage)

        val outcome = runBlocking { resolverClient.resolve(query) }
        val meta = outcome.meta()
        return finish(
            when (outcome) {
                is YouTubeResolveResult.Resolved -> afterResolved(
                    targetApp = targetApp,
                    query = query,
                    mode = mode,
                    resolved = outcome,
                    meta = meta,
                    installedNames = installedNames,
                    pinDecision = pinDecision,
                )
                else -> reject(
                    targetApp = targetApp,
                    query = query,
                    failure = outcome.statusName(),
                    path = "RESOLVE_ONLY",
                    meta = meta,
                    installedNames = installedNames,
                    resolverStatus = outcome.statusName(),
                )
            },
        )
    }

    private fun afterResolved(
        targetApp: String,
        query: String,
        mode: YouTubeLaunchMode,
        resolved: YouTubeResolveResult.Resolved,
        meta: YouTubeResolverMeta,
        installedNames: List<String>,
        pinDecision: SmartTubePinDecision,
    ): SmartTubePlayAutoResult {
        val id = YouTubeVideoIdParser.parse(resolved.videoId)
        if (id == null) {
            return reject(
                targetApp = targetApp,
                query = query,
                failure = "invalid_video_id",
                path = "RESOLVE_ONLY",
                meta = meta,
                installedNames = installedNames,
                resolverStatus = "RESOLVED",
                resolverSuccess = true,
                resolvedVideoId = resolved.videoId,
                resolvedTitle = resolved.title,
                targetUri = resolved.watchUrl,
                channel = resolved.channelTitle,
            )
        }
        val watch = when {
            YouTubeVideoIdParser.parse(resolved.watchUrl) == id -> resolved.watchUrl
            else -> YouTubeVideoIdParser.canonicalWatchUri(id)
        }
        val target = ResolvedMediaTarget(
            videoId = id,
            resolvedTitle = resolved.title,
            watchUrl = watch,
        )
        val base = resolvedBase(
            targetApp = targetApp,
            query = query,
            target = target,
            meta = meta,
            installedNames = installedNames,
            channel = resolved.channelTitle,
            cache = resolved.cache,
        )
        val form = options.launchForm
        if (form == null) {
            return base.copy(
                failure = SmartTubeLaunchAudit.UNPROVEN,
                launchAttempted = false,
                launchResult = "NOT_LAUNCHED",
                path = "RESOLVE_ONLY",
            )
        }
        val pinPackage = when (pinDecision) {
            is SmartTubePinDecision.Selected -> pinDecision.packageName
            SmartTubePinDecision.None -> {
                return base.copy(
                    failure = "smarttube_unavailable",
                    launchAttempted = false,
                    launchResult = "NOT_LAUNCHED",
                    path = "ONE_FORM",
                )
            }
            is SmartTubePinDecision.Ambiguous -> {
                return base.copy(
                    failure = "package_selection_required",
                    installedPackages = pinDecision.packages,
                    launchAttempted = false,
                    launchResult = "NOT_LAUNCHED",
                    path = "ONE_FORM",
                )
            }
            is SmartTubePinDecision.Rejected -> {
                return base.copy(
                    failure = pinDecision.reason,
                    targetPackage = options.selectedPackage,
                    launchAttempted = false,
                    launchResult = "NOT_LAUNCHED",
                    path = "ONE_FORM",
                )
            }
        }
        return launchOnce(
            base = base,
            target = target,
            form = form,
            pinPackage = pinPackage,
            mode = mode,
        )
    }

    private fun launchOnce(
        base: SmartTubePlayAutoResult,
        target: ResolvedMediaTarget,
        form: SmartTubeLaunchForm,
        pinPackage: String?,
        mode: YouTubeLaunchMode,
    ): SmartTubePlayAutoResult {
        if (SmartTubeHarnessIdentity.isHarness(pinPackage)) {
            return base.copy(
                failure = "harness_package_forbidden",
                launchForm = form,
                targetPackage = pinPackage,
                launchAttempted = false,
                launchResult = "NOT_LAUNCHED",
                path = "ONE_FORM",
            )
        }
        if (ForbiddenYouTubePackages.isYouTube(pinPackage)) {
            return base.copy(
                failure = "youtube_package_forbidden",
                launchForm = form,
                targetPackage = pinPackage,
                launchAttempted = false,
                launchResult = "NOT_LAUNCHED",
                path = "ONE_FORM",
            )
        }
        val built = SmartTubeIntentBuilder.build(form, target, pinPackage)
        val spec = when (built) {
            is SmartTubeIntentBuild.Failed -> {
                return base.copy(
                    failure = built.reason,
                    launchForm = form,
                    launchAttempted = false,
                    launchResult = "NOT_LAUNCHED",
                    path = "ONE_FORM",
                )
            }
            is SmartTubeIntentBuild.Ok -> built.spec
        }
        if (SmartTubeHarnessIdentity.isHarness(spec.packageName)) {
            return base.copy(
                failure = "harness_package_forbidden",
                launchForm = form,
                intentAction = spec.action,
                intentUri = spec.uri,
                targetPackage = spec.packageName,
                exactVideoTargetRequested = spec.exactVideoTargetRequested,
                launchAttempted = false,
                launchResult = "NOT_LAUNCHED",
                path = "ONE_FORM",
            )
        }
        if (ForbiddenYouTubePackages.isYouTube(spec.packageName)) {
            return base.copy(
                failure = "youtube_package_forbidden",
                launchForm = form,
                intentAction = spec.action,
                intentUri = spec.uri,
                targetPackage = spec.packageName,
                exactVideoTargetRequested = spec.exactVideoTargetRequested,
                launchAttempted = false,
                launchResult = "NOT_LAUNCHED",
                path = "ONE_FORM",
            )
        }
        val resolvedActivity = launcher.resolveActivity(spec)
        if (SmartTubeHarnessIdentity.isHarness(resolvedActivity?.packageName)) {
            return base.copy(
                failure = "would_launch_harness_not_smarttube",
                launchForm = form,
                intentAction = spec.action,
                intentUri = spec.uri,
                targetPackage = spec.packageName,
                resolveActivity = resolvedActivity?.component,
                resolveActivityPackage = resolvedActivity?.packageName,
                exactVideoTargetRequested = spec.exactVideoTargetRequested,
                launchAttempted = false,
                launchResult = "NOT_LAUNCHED",
                path = "ONE_FORM",
            )
        }
        if (ForbiddenYouTubePackages.isYouTube(resolvedActivity?.packageName)) {
            return base.copy(
                failure = "would_launch_youtube_not_smarttube",
                launchForm = form,
                intentAction = spec.action,
                intentUri = spec.uri,
                targetPackage = spec.packageName,
                resolveActivity = resolvedActivity?.component,
                resolveActivityPackage = resolvedActivity?.packageName,
                exactVideoTargetRequested = spec.exactVideoTargetRequested,
                launchAttempted = false,
                launchResult = "NOT_LAUNCHED",
                path = "ONE_FORM",
            )
        }
        if (form == SmartTubeLaunchForm.VIEW_WATCH_URL_UNPINNED &&
            !SmartTubePackageNames.isSafeSmartTubeTarget(resolvedActivity?.packageName)
        ) {
            return base.copy(
                failure = "unpinned_intent_would_not_target_smarttube",
                launchForm = form,
                intentAction = spec.action,
                intentUri = spec.uri,
                targetPackage = spec.packageName,
                resolveActivity = resolvedActivity?.component,
                resolveActivityPackage = resolvedActivity?.packageName,
                exactVideoTargetRequested = spec.exactVideoTargetRequested,
                launchAttempted = false,
                launchResult = "NOT_LAUNCHED",
                path = "ONE_FORM",
            )
        }
        val outcome = launcher.launch(spec, mode)
        return when (outcome) {
            is SmartTubeLaunchOutcome.DryRun -> base.copy(
                launchForm = form,
                intentAction = spec.action,
                intentUri = spec.uri,
                targetPackage = spec.packageName,
                resolveActivity = outcome.resolveActivity?.component,
                resolveActivityPackage = outcome.resolveActivity?.packageName,
                exactVideoTargetRequested = spec.exactVideoTargetRequested,
                launchAttempted = true,
                launchResult = "DRY_RUN",
                failure = null,
                path = "ONE_FORM",
            )
            is SmartTubeLaunchOutcome.Dispatched -> base.copy(
                launchForm = form,
                intentAction = spec.action,
                intentUri = spec.uri,
                targetPackage = spec.packageName,
                resolveActivity = outcome.resolveActivity?.component,
                resolveActivityPackage = outcome.resolveActivity?.packageName,
                exactVideoTargetRequested = spec.exactVideoTargetRequested,
                launchAttempted = true,
                launchResult = "DISPATCHED",
                failure = null,
                path = "ONE_FORM",
            )
            is SmartTubeLaunchOutcome.Failed -> base.copy(
                launchForm = form,
                intentAction = spec.action,
                intentUri = spec.uri,
                targetPackage = spec.packageName,
                resolveActivity = outcome.resolveActivity?.component,
                resolveActivityPackage = outcome.resolveActivity?.packageName,
                exactVideoTargetRequested = spec.exactVideoTargetRequested,
                launchAttempted = true,
                launchResult = "FAILED ${outcome.detail}",
                failure = "smarttube_launch_failed",
                path = "ONE_FORM",
            )
            is SmartTubeLaunchOutcome.Refused -> base.copy(
                launchForm = form,
                intentAction = spec.action,
                intentUri = spec.uri,
                targetPackage = spec.packageName,
                resolveActivity = outcome.resolveActivity?.component,
                resolveActivityPackage = outcome.resolveActivity?.packageName,
                exactVideoTargetRequested = spec.exactVideoTargetRequested,
                launchAttempted = false,
                launchResult = "REFUSED ${outcome.detail}",
                failure = outcome.detail,
                path = "ONE_FORM",
            )
        }
    }

    private fun resolvedBase(
        targetApp: String,
        query: String,
        target: ResolvedMediaTarget,
        meta: YouTubeResolverMeta,
        installedNames: List<String>,
        channel: String?,
        cache: String?,
    ): SmartTubePlayAutoResult = SmartTubePlayAutoResult(
        targetApp = targetApp,
        query = query,
        resolvedVideoId = target.videoId,
        resolvedTitle = target.resolvedTitle,
        targetUri = target.watchUrl,
        resolverSuccess = true,
        resolverStatus = "RESOLVED",
        resolverRequestAttempted = meta.requestAttempted,
        resolverBaseUrlConfigured = meta.baseUrlConfigured,
        resolverHttpStatus = meta.httpStatus,
        resolvedChannelTitle = channel,
        resolverCache = cache,
        resolverLatencyMs = meta.latencyMs,
        resolverHttps = meta.usedHttps,
        installedPackages = installedNames,
        launchAttempted = false,
        launchResult = "NOT_LAUNCHED",
        youtubeFallbackUsed = false,
        path = "RESOLVE_ONLY",
    )

    private fun reject(
        targetApp: String,
        query: String,
        failure: String,
        path: String,
        meta: YouTubeResolverMeta = YouTubeResolverMeta(),
        installedNames: List<String> = emptyList(),
        resolverStatus: String? = failure,
        resolverSuccess: Boolean = false,
        resolvedVideoId: String? = null,
        resolvedTitle: String? = null,
        targetUri: String? = null,
        channel: String? = null,
    ): SmartTubePlayAutoResult = SmartTubePlayAutoResult(
        targetApp = targetApp,
        query = query,
        resolvedVideoId = resolvedVideoId,
        resolvedTitle = resolvedTitle,
        targetUri = targetUri,
        resolverSuccess = resolverSuccess,
        resolverStatus = resolverStatus,
        resolverRequestAttempted = meta.requestAttempted,
        resolverBaseUrlConfigured = meta.baseUrlConfigured,
        resolverHttpStatus = meta.httpStatus,
        resolvedChannelTitle = channel,
        resolverLatencyMs = meta.latencyMs,
        resolverHttps = meta.usedHttps,
        installedPackages = installedNames,
        launchAttempted = false,
        launchResult = "NOT_LAUNCHED",
        youtubeFallbackUsed = false,
        path = path,
        failure = failure,
    )

    private fun finish(result: SmartTubePlayAutoResult): SmartTubePlayAutoResult {
        lastResult = result
        return result
    }
}
