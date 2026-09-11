package org.stypox.dicio.io.session

import android.content.Context
import org.stypox.dicio.youtubeplayauto.HttpYouTubeResolverClient
import org.stypox.dicio.youtubeplayauto.ParsedYouTubeContentResolver
import org.stypox.dicio.youtubeplayauto.PlayAutoRequest
import org.stypox.dicio.youtubeplayauto.YouTubeInAppSelector
import org.stypox.dicio.youtubeplayauto.YouTubeLaunchMode
import org.stypox.dicio.youtubeplayauto.YouTubeMediaAdapter
import org.stypox.dicio.youtubeplayauto.YouTubePlayAutoDriver
import org.stypox.dicio.youtubeplayauto.YouTubePlayAutoOptions
import org.stypox.dicio.youtubeplayauto.YouTubePlayAutoResult
import org.stypox.dicio.youtubeplayauto.YouTubeResolverClient
import org.stypox.dicio.youtubeplayauto.YouTubeResolverEndpoint
import org.stypox.dicio.youtubeplayauto.YouTubeRuntime
import org.stypox.dicio.youtubeplayauto.YouTubeSelectOutcome
import org.stypox.dicio.youtubeplayauto.AndroidYouTubeRuntime

/**
 * Narrow production adapter: PlayMedia query → proven YouTube PlayAuto driver.
 *
 * One [play] call builds a fresh driver (the driver claims a single request) and
 * never falls back to the legacy YouTube search ACTION_VIEW, Accessibility, Cast,
 * or media keys.
 */
object YouTubeProductionJack {
    fun create(context: Context): YouTubePlayAutoPort {
        val appContext = context.applicationContext
        return DriverBackedYouTubePlayAutoPort(
            driverFactory = {
                driver(
                    runtime = AndroidYouTubeRuntime(appContext),
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
        runtime: YouTubeRuntime,
        resolverClient: YouTubeResolverClient,
    ): YouTubePlayAutoDriver = YouTubePlayAutoDriver(
        adapter = YouTubeMediaAdapter(runtime),
        selector = DisabledYouTubeInAppSelector,
        resolver = ParsedYouTubeContentResolver,
        options = YouTubePlayAutoOptions(accessibilityFallbackEnabled = false),
        resolverClient = resolverClient,
    )
}

class DriverBackedYouTubePlayAutoPort(
    private val driverFactory: () -> YouTubePlayAutoDriver,
) : YouTubePlayAutoPort {
    override fun play(query: String): YouTubeProductionJackResult {
        val driver = driverFactory()
        val result = driver.execute(
            PlayAutoRequest(targetApp = "YouTube", query = query),
            YouTubeLaunchMode.DEVICE_TEST,
        )
        return result.toJackResult(playAutoRequestCount = driver.executeCount)
    }
}

internal object DisabledYouTubeInAppSelector : YouTubeInAppSelector {
    override fun isAvailable(): Boolean = false

    override fun selectAndPlay(
        query: String,
        youtubePackage: String,
        mode: YouTubeLaunchMode,
    ): YouTubeSelectOutcome = YouTubeSelectOutcome.Unavailable("production_accessibility_disabled")
}

private fun YouTubePlayAutoResult.toJackResult(
    playAutoRequestCount: Int,
): YouTubeProductionJackResult {
    val launched = failure == null &&
        resolverSuccess &&
        !targetUri.isNullOrBlank() &&
        !searchOpened &&
        !accessibilityFallbackUsed
    return YouTubeProductionJackResult(
        query = query,
        playAutoRequestCount = playAutoRequestCount,
        launched = launched,
        launchCount = if (launched) 1 else 0,
        watchUrl = targetUri,
        videoId = resolvedVideoId,
        failure = failure,
        path = path,
        searchOpened = searchOpened,
        accessibilityFallbackUsed = accessibilityFallbackUsed,
        castApisUsed = castApisUsed,
        mediaKeysSent = mediaKeysSent,
        resolverStatus = resolverStatus,
        resolvedTitle = resolvedTitle,
    )
}
