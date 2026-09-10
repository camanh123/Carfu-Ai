package org.stypox.dicio.youtubeplayauto

/**
 * Deterministic test double. Never contacts the live resolver or Google.
 */
class FakeYouTubeResolverClient(
    var result: YouTubeResolveResult = YouTubeResolveResult.ResolverUnavailable(),
    var resolveCount: Int = 0,
    var lastQuery: String? = null,
    val queries: MutableList<String> = mutableListOf(),
) : YouTubeResolverClient {
    override suspend fun resolve(
        query: String,
        lang: String,
        region: String,
    ): YouTubeResolveResult {
        resolveCount += 1
        lastQuery = query
        queries += query
        return result
    }
}
