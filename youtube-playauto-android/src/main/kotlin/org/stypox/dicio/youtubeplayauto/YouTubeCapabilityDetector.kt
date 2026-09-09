package org.stypox.dicio.youtubeplayauto

import org.stypox.dicio.playauto.core.PlaybackCapability

data class YouTubeCapabilitySnapshot(
    val installed: Boolean,
    val packageName: String?,
    val launchActivity: String?,
    val versionName: String?,
    val openAppResolvable: Boolean,
    val searchResolvable: Boolean,
    /** PackageManager can resolve a watch URI. Not a content-id claim. */
    val deepLinkActivityResolvable: Boolean,
    val directPlaySupported: Boolean,
    /** Capabilities advertised to PlayAutoEngine. DIRECT_PLAY is never included. */
    val engineCapabilities: Set<PlaybackCapability>,
    val notes: String,
)

class YouTubeCapabilityDetector(
    private val runtime: YouTubeRuntime,
) {
    companion object {
        /**
         * Sentinel used only to ask PackageManager whether a watch VIEW intent resolves.
         * Not a content claim and never used as a song mapping.
         */
        const val ACTIVITY_PROBE_VIDEO_ID = "AAAAAAAAAAA"
    }

    fun detect(): YouTubeCapabilitySnapshot {
        val installed = runtime.installedPackage()
        if (installed == null) {
            return YouTubeCapabilitySnapshot(
                installed = false,
                packageName = null,
                launchActivity = null,
                versionName = null,
                openAppResolvable = false,
                searchResolvable = false,
                deepLinkActivityResolvable = false,
                directPlaySupported = false,
                engineCapabilities = emptySet(),
                notes = "youtube_unavailable",
            )
        }
        val pkg = installed.packageName
        val openApp = runtime.canResolve(
            specOf(YouTubeOpenAppStrategy.build(YouTubeStrategyInput(pkg, query = "probe"))),
        )
        val search = runtime.canResolve(
            specOf(YouTubeSearchStrategy.build(YouTubeStrategyInput(pkg, query = "probe"))),
        )
        val deepLink = runtime.canResolve(
            specOf(
                YouTubeDeepLinkStrategy.build(
                    YouTubeStrategyInput(
                        packageName = pkg,
                        query = "probe",
                        resolved = ResolvedYouTubeTarget(
                            videoId = ACTIVITY_PROBE_VIDEO_ID,
                            canonicalUri = YouTubeVideoIdParser.canonicalWatchUri(ACTIVITY_PROBE_VIDEO_ID),
                            source = "activity_probe",
                        ),
                    ),
                ),
            ),
        )
        val caps = linkedSetOf<PlaybackCapability>()
        // PlayAutoEngine picks the strongest advertised capability and cannot fall back
        // to a weaker strategy on the same adapter. Do not advertise DEEP_LINK without a
        // resolver that produces video ids for the request (Phase 4.7 has none by default).
        if (search) caps += PlaybackCapability.SEARCH
        if (openApp) caps += PlaybackCapability.OPEN_APP
        return YouTubeCapabilitySnapshot(
            installed = true,
            packageName = pkg,
            launchActivity = installed.launchActivity,
            versionName = installed.versionName,
            openAppResolvable = openApp,
            searchResolvable = search,
            deepLinkActivityResolvable = deepLink,
            directPlaySupported = false,
            engineCapabilities = caps,
            notes = buildString {
                append("DEEP_LINK constructable if a video id is supplied; not advertised to PlayAutoEngine. ")
                append("DIRECT_PLAY not proven.")
            },
        )
    }

    private fun specOf(build: YouTubeStrategyBuild): YouTubeLaunchSpec =
        (build as? YouTubeStrategyBuild.Ok)?.spec
            ?: YouTubeLaunchSpec(
                strategy = org.stypox.dicio.playauto.core.PlaybackStrategy.OPEN_APP,
                action = YouTubeLaunchSpec.ACTION_MAIN,
                packageName = null,
            )
}
