package org.stypox.dicio.youtubeplayauto

import org.stypox.dicio.playauto.core.PlaybackFailureReason
import org.stypox.dicio.playauto.core.PlaybackStrategy
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class YouTubeStrategyInput(
    val packageName: String?,
    val query: String,
    val resolved: ResolvedYouTubeTarget? = null,
)

sealed class YouTubeStrategyBuild {
    data class Ok(val spec: YouTubeLaunchSpec) : YouTubeStrategyBuild()
    data class Failed(
        val reason: PlaybackFailureReason,
        val detail: String,
    ) : YouTubeStrategyBuild()
}

interface YouTubeLaunchStrategy {
    val strategy: PlaybackStrategy
    fun build(input: YouTubeStrategyInput): YouTubeStrategyBuild
}

object YouTubeOpenAppStrategy : YouTubeLaunchStrategy {
    override val strategy: PlaybackStrategy = PlaybackStrategy.OPEN_APP

    override fun build(input: YouTubeStrategyInput): YouTubeStrategyBuild {
        val pkg = input.packageName
            ?: return YouTubeStrategyBuild.Failed(
                PlaybackFailureReason.PROVIDER_UNAVAILABLE,
                "youtube_package_missing",
            )
        return YouTubeStrategyBuild.Ok(
            YouTubeLaunchSpec(
                strategy = strategy,
                action = YouTubeLaunchSpec.ACTION_MAIN,
                packageName = pkg,
                categories = setOf(YouTubeLaunchSpec.CATEGORY_LAUNCHER),
            ),
        )
    }
}

object YouTubeSearchStrategy : YouTubeLaunchStrategy {
    override val strategy: PlaybackStrategy = PlaybackStrategy.SEARCH

    override fun build(input: YouTubeStrategyInput): YouTubeStrategyBuild {
        val query = input.query.trim()
        if (query.isEmpty()) {
            return YouTubeStrategyBuild.Failed(
                PlaybackFailureReason.INVALID_REQUEST,
                "blank_query",
            )
        }
        val pkg = input.packageName
            ?: return YouTubeStrategyBuild.Failed(
                PlaybackFailureReason.PROVIDER_UNAVAILABLE,
                "youtube_package_missing",
            )
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
        return YouTubeStrategyBuild.Ok(
            YouTubeLaunchSpec(
                strategy = strategy,
                action = YouTubeLaunchSpec.ACTION_VIEW,
                packageName = pkg,
                uri = "https://www.youtube.com/results?search_query=$encoded",
                extraQuery = query,
            ),
        )
    }
}

object YouTubeDeepLinkStrategy : YouTubeLaunchStrategy {
    override val strategy: PlaybackStrategy = PlaybackStrategy.DEEP_LINK

    override fun build(input: YouTubeStrategyInput): YouTubeStrategyBuild {
        val resolved = input.resolved
            ?: return YouTubeStrategyBuild.Failed(
                PlaybackFailureReason.RESOLUTION_FAILED,
                "no_resolved_video_target",
            )
        val pkg = input.packageName
            ?: return YouTubeStrategyBuild.Failed(
                PlaybackFailureReason.PROVIDER_UNAVAILABLE,
                "youtube_package_missing",
            )
        return YouTubeStrategyBuild.Ok(
            YouTubeLaunchSpec(
                strategy = strategy,
                action = YouTubeLaunchSpec.ACTION_VIEW,
                packageName = pkg,
                uri = resolved.canonicalUri,
                extraQuery = input.query.trim().ifEmpty { null },
            ),
        )
    }
}

/**
 * DIRECT_PLAY is not implemented. Opening a watch URL is [PlaybackStrategy.DEEP_LINK],
 * not autoplay. There is no public third-party YouTube autoplay API used here.
 */
object YouTubeDirectPlayStrategy : YouTubeLaunchStrategy {
    override val strategy: PlaybackStrategy = PlaybackStrategy.DIRECT_PLAY

    const val UNSUPPORTED_DETAIL =
        "NOT PROVEN: no public third-party YouTube autoplay API; watch URI is DEEP_LINK, not playback confirmation"

    override fun build(input: YouTubeStrategyInput): YouTubeStrategyBuild {
        if (input.resolved == null) {
            return YouTubeStrategyBuild.Failed(
                PlaybackFailureReason.NO_PLAYBACK_STRATEGY,
                "direct_play_requires_resolved_target_and_supported_implementation",
            )
        }
        return YouTubeStrategyBuild.Failed(
            PlaybackFailureReason.NO_PLAYBACK_STRATEGY,
            UNSUPPORTED_DETAIL,
        )
    }
}
