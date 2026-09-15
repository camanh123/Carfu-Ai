package org.stypox.dicio.smarttubeplayauto

import org.stypox.dicio.youtubeplayauto.YouTubeLaunchMode

/** In-memory launcher. Never starts an app. Test / dry diagnostic only. */
class FakeSmartTubeLauncher(
    var catalogInstalled: List<String> = listOf(SmartTubeCatalog.TEAMSMART),
    var extraInstalled: List<SmartTubeInstalledPackage> = emptyList(),
    var resolvable: Boolean = true,
    var launchSucceeds: Boolean = true,
    var resolveActivityOverride: SmartTubeResolveActivity? = null,
) : SmartTubeLauncher {
    val launches = mutableListOf<SmartTubeLaunchSpec>()
    val receivedTargets = mutableListOf<ResolvedMediaTarget>()
    var launchCount: Int = 0
        private set
    var lastSpec: SmartTubeLaunchSpec? = null
        private set
    var lastMode: YouTubeLaunchMode? = null
        private set
    var youtubePackageLaunchCount: Int = 0
        private set

    override fun installedPackages(): List<SmartTubeInstalledPackage> {
        val catalog = catalogInstalled.map { pkg ->
            SmartTubeInstalledPackage(
                packageName = pkg,
                versionName = "fake",
                launchActivity = "$pkg/.fake",
                source = "catalog_fixture",
            )
        }
        return catalog + extraInstalled
    }

    override fun resolveActivity(spec: SmartTubeLaunchSpec): SmartTubeResolveActivity? {
        if (resolveActivityOverride != null) return resolveActivityOverride
        if (!resolvable) return null
        val pkg = spec.packageName ?: preferredInstalledPackage() ?: return null
        if (spec.packageName != null && spec.packageName !in installedPackages().map { it.packageName }) {
            return null
        }
        return SmartTubeResolveActivity(
            component = "$pkg/.FakeActivity",
            packageName = pkg,
        )
    }

    override fun launch(
        spec: SmartTubeLaunchSpec,
        mode: YouTubeLaunchMode,
    ): SmartTubeLaunchOutcome {
        lastSpec = spec
        lastMode = mode
        if (ForbiddenYouTubePackages.isYouTube(spec.packageName)) {
            youtubePackageLaunchCount += 1
            return SmartTubeLaunchOutcome.Refused(
                detail = "youtube_package_forbidden",
                spec = spec,
            )
        }
        launches += spec
        spec.videoId?.let { id ->
            receivedTargets += ResolvedMediaTarget(
                videoId = id,
                resolvedTitle = spec.resolvedTitle,
                watchUrl = spec.uri ?: "",
            )
        }
        launchCount += 1
        val resolved = resolveActivity(spec)
        if (mode == YouTubeLaunchMode.DRY_RUN) {
            return SmartTubeLaunchOutcome.DryRun(spec, resolved)
        }
        if (!launchSucceeds) {
            return SmartTubeLaunchOutcome.Failed("dispatch_failed", spec, resolved)
        }
        if (resolved == null && spec.packageName != null) {
            return SmartTubeLaunchOutcome.Failed("activity_unresolved", spec, resolved)
        }
        return SmartTubeLaunchOutcome.Dispatched(spec, resolved)
    }
}
