package org.stypox.dicio.youtubeplayauto

import org.stypox.dicio.playauto.core.PlaybackStrategy

/** In-memory YouTube runtime for unit tests. Never launches an app. */
class FakeYouTubeRuntime(
    var installation: YouTubePackageInfo? = YouTubePackageInfo(
        packageName = YouTubeCandidatePackages.OFFICIAL,
        launchActivity = "com.google.android.youtube.HomeActivity",
        versionName = "fake",
    ),
    var resolvable: Set<PlaybackStrategy> = setOf(
        PlaybackStrategy.OPEN_APP,
        PlaybackStrategy.SEARCH,
        PlaybackStrategy.DEEP_LINK,
    ),
    var dispatchSucceeds: Boolean = true,
) : YouTubeRuntime {
    var dispatchCount: Int = 0
    var lastSpec: YouTubeLaunchSpec? = null
    var lastMode: YouTubeLaunchMode? = null

    override fun installedPackage(): YouTubePackageInfo? = installation

    override fun canResolve(spec: YouTubeLaunchSpec): Boolean {
        if (installation == null) return false
        if (spec.packageName != null && spec.packageName != installation?.packageName) return false
        return spec.strategy in resolvable
    }

    override fun dispatch(
        spec: YouTubeLaunchSpec,
        mode: YouTubeLaunchMode,
    ): YouTubeDispatchOutcome {
        lastSpec = spec
        lastMode = mode
        if (mode == YouTubeLaunchMode.DRY_RUN) {
            dispatchCount += 1
            return YouTubeDispatchOutcome.DryRun(spec)
        }
        if (!canResolve(spec) || !dispatchSucceeds) {
            return YouTubeDispatchOutcome.Failed("dispatch_failed")
        }
        dispatchCount += 1
        return YouTubeDispatchOutcome.Dispatched(
            spec = spec,
            provenance = YouTubeProvenance.INTENT_DISPATCHED,
        )
    }
}
