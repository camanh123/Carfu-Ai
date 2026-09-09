package org.stypox.dicio.youtubeplayauto

/**
 * User-supplied or resolver-supplied YouTube content. Never invented from a song title.
 */
data class ResolvedYouTubeTarget(
    val videoId: String,
    val canonicalUri: String,
    val title: String? = null,
    val confidence: Float? = null,
    val source: String,
)

sealed class YouTubeContentResolution {
    data class Resolved(val target: ResolvedYouTubeTarget) : YouTubeContentResolution()
    data class Unresolved(val query: String, val reason: String) : YouTubeContentResolution()
}

/**
 * Pluggable query → video mapping. Phase 4.7 ships [NoOpYouTubeContentResolver]:
 * there is no Android-only API that maps a title to a video id without a search/API layer.
 */
fun interface YouTubeContentResolver {
    fun resolveQuery(query: String): YouTubeContentResolution
}

object NoOpYouTubeContentResolver : YouTubeContentResolver {
    override fun resolveQuery(query: String): YouTubeContentResolution =
        YouTubeContentResolution.Unresolved(
            query = query,
            reason = "no_android_title_to_video_id_resolver",
        )
}

/**
 * Test / harness injection. Does not scrape the network. Keys are exact trimmed queries
 * or explicit video ids pasted by the tester.
 */
class InjectedYouTubeContentResolver(
    private val byQuery: Map<String, ResolvedYouTubeTarget> = emptyMap(),
) : YouTubeContentResolver {
    override fun resolveQuery(query: String): YouTubeContentResolution {
        val key = query.trim()
        val hit = byQuery[key] ?: return YouTubeContentResolution.Unresolved(
            query = key,
            reason = "injected_miss",
        )
        return YouTubeContentResolution.Resolved(hit)
    }
}
