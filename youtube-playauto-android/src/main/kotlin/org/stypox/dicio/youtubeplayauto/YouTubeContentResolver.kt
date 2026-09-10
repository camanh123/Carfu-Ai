package org.stypox.dicio.youtubeplayauto

/**
 * User-supplied or resolver-supplied YouTube content. Never invented from a song title
 * without a search backend.
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
 * Query → video mapping. Accessibility is not used.
 *
 * Local (no network): parse an already-known video id or watch URL.
 * Title queries need [SearchingYouTubeContentResolver] (public search boundary)
 * or an injected map. There is no Android-only title→id API.
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
 * SOURCE-PROVEN local path: the query already is a video id or watch/embed/shorts URL.
 * Does not hardcode songs. Does not search.
 */
object ParsedYouTubeContentResolver : YouTubeContentResolver {
    override fun resolveQuery(query: String): YouTubeContentResolution {
        val key = query.trim()
        if (key.isEmpty()) {
            return YouTubeContentResolution.Unresolved(query = key, reason = "blank_query")
        }
        val id = YouTubeVideoIdParser.parse(key)
            ?: return YouTubeContentResolution.Unresolved(
                query = key,
                reason = "not_a_video_id_or_watch_url",
            )
        return YouTubeContentResolution.Resolved(
            ResolvedYouTubeTarget(
                videoId = id,
                canonicalUri = YouTubeVideoIdParser.canonicalWatchUri(id),
                title = null,
                confidence = 1.0f,
                source = "parsed_id_or_watch_url",
            ),
        )
    }
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

/**
 * Uses [YouTubeQuerySearchClient] then title-scores hits. Remix/karaoke/cover are
 * penalized when the query did not ask for them. Not Accessibility.
 */
class SearchingYouTubeContentResolver(
    private val client: YouTubeQuerySearchClient,
) : YouTubeContentResolver {
    override fun resolveQuery(query: String): YouTubeContentResolution {
        val key = query.trim()
        if (key.isEmpty()) {
            return YouTubeContentResolution.Unresolved(query = key, reason = "blank_query")
        }
        return when (val result = client.search(key)) {
            is YouTubeQuerySearchResult.Failed ->
                YouTubeContentResolution.Unresolved(query = key, reason = result.reason)
            is YouTubeQuerySearchResult.Hits -> pick(key, result.items)
        }
    }

    private fun pick(
        query: String,
        items: List<YouTubeQuerySearchHit>,
    ): YouTubeContentResolution {
        val ranked = items.mapNotNull { hit ->
            val id = YouTubeVideoIdParser.parse(hit.videoId) ?: return@mapNotNull null
            val title = hit.title.ifBlank { id }
            val score = YouTubeTitleMatcher.score(title, query) -
                YouTubeSearchUiClassifier.penalizeUnwantedCompletion(title, query)
            if (!YouTubeTitleMatcher.isSelectable(score) && hit.title.isNotBlank()) {
                return@mapNotNull null
            }
            Triple(hit.copy(videoId = id), score, title)
        }
        val best = if (ranked.any { it.second > 0 }) {
            ranked.maxWithOrNull(
                compareBy<Triple<YouTubeQuerySearchHit, Int, String>> { it.second }
                    .thenBy { it.third.length },
            )
        } else {
            null
        }
        if (best == null) {
            return YouTubeContentResolution.Unresolved(
                query = query,
                reason = "no_matching_public_search_hit",
            )
        }
        val hit = best.first
        return YouTubeContentResolution.Resolved(
            ResolvedYouTubeTarget(
                videoId = hit.videoId,
                canonicalUri = YouTubeVideoIdParser.canonicalWatchUri(hit.videoId),
                title = hit.title.ifBlank { null },
                confidence = (best.second.coerceIn(0, 100)) / 100f,
                source = "public_html_search",
            ),
        )
    }
}

/**
 * Try local parse first, then optional search, then injected maps.
 * First [YouTubeContentResolution.Resolved] wins.
 */
class ChainedYouTubeContentResolver(
    private val delegates: List<YouTubeContentResolver>,
) : YouTubeContentResolver {
    constructor(vararg delegates: YouTubeContentResolver) : this(delegates.toList())

    override fun resolveQuery(query: String): YouTubeContentResolution {
        val key = query.trim()
        if (key.isEmpty()) {
            return YouTubeContentResolution.Unresolved(query = key, reason = "blank_query")
        }
        var lastUnresolved: YouTubeContentResolution.Unresolved? = null
        for (delegate in delegates) {
            when (val result = delegate.resolveQuery(key)) {
                is YouTubeContentResolution.Resolved -> return result
                is YouTubeContentResolution.Unresolved -> lastUnresolved = result
            }
        }
        return lastUnresolved ?: YouTubeContentResolution.Unresolved(
            query = key,
            reason = YouTubePublicLaunchAudit.TITLE_TO_VIDEO_ID_LOCAL,
        )
    }
}

fun defaultHarnessContentResolver(
    searchClient: YouTubeQuerySearchClient,
): YouTubeContentResolver = ChainedYouTubeContentResolver(
    ParsedYouTubeContentResolver,
    SearchingYouTubeContentResolver(searchClient),
)
