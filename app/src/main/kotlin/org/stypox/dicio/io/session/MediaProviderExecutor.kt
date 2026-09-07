package org.stypox.dicio.io.session

import org.stypox.dicio.skills.carfu.CarfuDialer
import org.stypox.dicio.skills.carfu.CarfuLaunchSpec
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Lightweight media-provider boundary. Unknown providers fail safely.
 */
object MediaProviderExecutor {
    data class MediaLaunch(
        val spec: CarfuLaunchSpec,
        val query: String,
        val provider: String,
        /** True when a public intent can supply the query (search), not guaranteed autoplay. */
        val searchRoute: Boolean,
    )

    fun build(query: String, provider: String?): Result {
        val q = query.trim()
        if (q.isEmpty()) return Result.Unsupported("empty_query")
        val p = provider?.trim().orEmpty()
        if (p.isEmpty()) return Result.Unsupported("missing_provider")
        val folded = VietnameseTranscript.foldForMatch(p)
        return when (folded) {
            "youtube", "you tube", "yt" -> Result.Ok(youtubeSearch(q))
            else -> Result.Unsupported("unknown_provider:$p")
        }
    }

    fun youtubeSearch(query: String): MediaLaunch {
        val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())
            .replace("+", "%20")
        // Public YouTube search deep link — deterministic query delivery, not exact autoplay.
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
        data class Unsupported(val reason: String) : Result()
    }
}
