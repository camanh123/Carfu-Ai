package org.stypox.dicio.io.session

import android.content.Context
import org.stypox.dicio.smarttubeplayauto.AndroidSmartTubeLauncher
import org.stypox.dicio.smarttubeplayauto.SmartTubeLaunchAudit
import org.stypox.dicio.smarttubeplayauto.SmartTubeLauncher
import org.stypox.dicio.smarttubeplayauto.SmartTubePlayAutoDriver
import org.stypox.dicio.smarttubeplayauto.SmartTubePlayAutoResult
import org.stypox.dicio.smarttubeplayauto.SmartTubeProductionPolicy
import org.stypox.dicio.youtubeplayauto.HttpYouTubeResolverClient
import org.stypox.dicio.youtubeplayauto.PlayAutoRequest
import org.stypox.dicio.youtubeplayauto.YouTubeLaunchMode
import org.stypox.dicio.youtubeplayauto.YouTubeResolverClient
import org.stypox.dicio.youtubeplayauto.YouTubeResolverEndpoint

/**
 * Narrow production adapter: PlayMedia query → DEVICE-PROVEN SmartTube Probe A.
 *
 * One [play] call builds a fresh driver (the driver claims a single request) and
 * never falls back to YouTube, beta, the diagnostic harness, Accessibility, Cast,
 * or media keys.
 */
object SmartTubeProductionJack {
    fun create(context: Context): SmartTubePlayAutoPort {
        val appContext = context.applicationContext
        return DriverBackedSmartTubePlayAutoPort(
            driverFactory = {
                driver(
                    launcher = AndroidSmartTubeLauncher(appContext),
                    resolverClient = HttpYouTubeResolverClient(
                        baseUrlProvider = {
                            YouTubeResolverEndpoint.DEFAULT_PUBLIC_HTTPS_BASE_URL
                        },
                    ),
                )
            },
        )
    }

    fun driver(
        launcher: SmartTubeLauncher,
        resolverClient: YouTubeResolverClient,
    ): SmartTubePlayAutoDriver = SmartTubePlayAutoDriver(
        resolverClient = resolverClient,
        launcher = launcher,
        options = SmartTubeProductionPolicy.options(),
    )
}

class DriverBackedSmartTubePlayAutoPort(
    private val driverFactory: () -> SmartTubePlayAutoDriver,
) : SmartTubePlayAutoPort {
    override fun play(query: String): SmartTubeProductionJackResult {
        val driver = driverFactory()
        val result = driver.execute(
            PlayAutoRequest(targetApp = "SmartTube", query = query),
            YouTubeLaunchMode.DEVICE_TEST,
        )
        return result.toJackResult(playAutoRequestCount = driver.executeCount)
    }
}

private fun SmartTubePlayAutoResult.toJackResult(
    playAutoRequestCount: Int,
): SmartTubeProductionJackResult {
    val forbidden = SmartTubeProductionPolicy.isForbiddenTarget(targetPackage) ||
        SmartTubeProductionPolicy.isForbiddenTarget(resolveActivityPackage)
    val approvedPin = SmartTubeProductionPolicy.isApprovedTarget(targetPackage)
    val launched = failure == null &&
        resolverSuccess &&
        launchAttempted &&
        launchResult == "DISPATCHED" &&
        approvedPin &&
        !forbidden &&
        !targetUri.isNullOrBlank() &&
        youtubeFallbackUsed.not() &&
        accessibilityUsed.not() &&
        intentAction == SmartTubeLaunchAudit.ACTION_VIEW
    val failureOut = when {
        forbidden -> "forbidden_smarttube_target"
        failure == "selected_package_not_device_installed" ||
            failure == "smarttube_unavailable" ||
            failure == "package_selection_required" -> "smarttube_unavailable"
        else -> failure
    }
    return SmartTubeProductionJackResult(
        query = query,
        playAutoRequestCount = playAutoRequestCount,
        launched = launched,
        launchCount = if (launched) 1 else 0,
        watchUrl = if (launched) targetUri else null,
        videoId = resolvedVideoId,
        resolvedTitle = resolvedTitle,
        targetPackage = targetPackage,
        intentAction = intentAction,
        failure = if (launched) null else failureOut,
        path = path,
        youtubeFallbackUsed = youtubeFallbackUsed,
        accessibilityUsed = accessibilityUsed,
        castApisUsed = castApisUsed,
        mediaKeysSent = mediaKeysSent,
        resolverStatus = resolverStatus,
    )
}
