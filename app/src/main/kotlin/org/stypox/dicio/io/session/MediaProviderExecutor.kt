package org.stypox.dicio.io.session

import org.stypox.dicio.skills.carfu.CarfuDialer
import org.stypox.dicio.skills.carfu.CarfuLaunchSpec
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Lightweight media-provider boundary. Unknown providers fail safely.
 *
 * YouTube PlayMedia is owned by [YouTubePlayAutoPort] (Phase 4.9.3). The legacy
 * search ACTION_VIEW helpers remain for audit/tests and must not run on the
 * production YouTube route.
 */
object MediaProviderExecutor {
    @Volatile
    var legacyYoutubeSearchCount: Int = 0
        private set

    fun resetForTests() {
        legacyYoutubeSearchCount = 0
    }

    data class MediaLaunch(
        val spec: CarfuLaunchSpec,
        val query: String,
        val provider: String,
        /** True when a public intent can supply the query (search), not guaranteed autoplay. */
        val searchRoute: Boolean,
    )

    fun isYouTubeProvider(provider: String?): Boolean {
        val folded = VietnameseTranscript.foldForMatch(provider?.trim().orEmpty())
        return folded == "youtube" || folded == "you tube" || folded == "yt"
    }

    fun build(query: String, provider: String?): Result {
        val q = query.trim()
        if (q.isEmpty()) return Result.Unsupported("empty_query")
        val p = provider?.trim().orEmpty()
        if (p.isEmpty()) return Result.Unsupported("missing_provider")
        if (isYouTubeProvider(p)) return Result.YouTubePlayAuto(q)
        return Result.Unsupported("unknown_provider:$p")
    }

    fun youtubeSearch(query: String): MediaLaunch {
        legacyYoutubeSearchCount += 1
        val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())
            .replace("+", "%20")
        // Legacy public YouTube search deep link — not the production YouTube owner.
        val data = "https://www.youtube.com/results?search_query=$encoded"
        return MediaLaunch(
            spec = CarfuLaunchSpec(
                action = CarfuDialer.ACTION_VIEW,
                packageName = "com.google.android.youtube",
                data = data,
                extraQuery = query.trim(),
            ),
            query = query.trim(),
            provider = "YouTube",
            searchRoute = true,
        )
    }

    /** Fallback VIEW without package pin when YouTube package missing. */
    fun youtubeSearchGeneric(query: String): MediaLaunch {
        val base = youtubeSearch(query)
        return base.copy(spec = base.spec.copy(packageName = null))
    }

    sealed class Result {
        data class Ok(val launch: MediaLaunch) : Result()
        data class YouTubePlayAuto(val query: String) : Result()
        data class Unsupported(val reason: String) : Result()
    }
}
