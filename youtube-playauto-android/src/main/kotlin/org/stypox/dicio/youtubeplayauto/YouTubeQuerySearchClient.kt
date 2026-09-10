package org.stypox.dicio.youtubeplayauto

/**
 * Separate boundary for title → candidate videos.
 * Not Accessibility. Not the YouTube Data API (no API key).
 *
 * [YouTubePublicSearchHtmlParser] consumes public search-page HTML when a
 * client supplies it. That HTML endpoint is **not** an official developer API.
 */
data class YouTubeQuerySearchHit(
    val videoId: String,
    val title: String,
)

sealed class YouTubeQuerySearchResult {
    data class Hits(val items: List<YouTubeQuerySearchHit>) : YouTubeQuerySearchResult()
    data class Failed(val reason: String) : YouTubeQuerySearchResult()
}

fun interface YouTubeQuerySearchClient {
    fun search(query: String): YouTubeQuerySearchResult
}

class FakeYouTubeQuerySearchClient(
    var result: YouTubeQuerySearchResult = YouTubeQuerySearchResult.Failed("fake_unconfigured"),
    var searchCount: Int = 0,
    var lastQuery: String? = null,
) : YouTubeQuerySearchClient {
    override fun search(query: String): YouTubeQuerySearchResult {
        searchCount += 1
        lastQuery = query
        return result
    }
}

/**
 * Parses public YouTube results HTML for videoRenderer blocks.
 * Not Accessibility. Fails closed if the page has no ids (consent wall, etc.).
 */
object YouTubePublicSearchHtmlParser {
    private val VIDEO_ID = Regex("\"videoId\"\\s*:\\s*\"([A-Za-z0-9_-]{11})\"")
    private val TITLE = Regex("\"title\"\\s*:\\s*\\{\"runs\"\\s*:\\s*\\[\\{\"text\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")

    fun parse(html: String): List<YouTubeQuerySearchHit> {
        if (html.isBlank()) return emptyList()
        val parts = html.split("\"videoRenderer\"")
        if (parts.size <= 1) {
            return VIDEO_ID.findAll(html).map { match ->
                YouTubeQuerySearchHit(videoId = match.groupValues[1], title = "")
            }.distinctBy { it.videoId }.toList()
        }
        val out = ArrayList<YouTubeQuerySearchHit>()
        val seen = HashSet<String>()
        for (part in parts.drop(1)) {
            val chunk = if (part.length > 4000) part.substring(0, 4000) else part
            val id = VIDEO_ID.find(chunk)?.groupValues?.getOrNull(1) ?: continue
            if (!seen.add(id)) continue
            val rawTitle = TITLE.find(chunk)?.groupValues?.getOrNull(1).orEmpty()
            out += YouTubeQuerySearchHit(videoId = id, title = unescapeJson(rawTitle))
        }
        return out
    }

    private fun unescapeJson(raw: String): String {
        if (raw.isEmpty()) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                when (val escaped = raw[i + 1]) {
                    '"' -> {
                        out.append('"'); i += 2; continue
                    }
                    '\\' -> {
                        out.append('\\'); i += 2; continue
                    }
                    'n' -> {
                        out.append('\n'); i += 2; continue
                    }
                    'u' -> {
                        if (i + 5 < raw.length) {
                            val hex = raw.substring(i + 2, i + 6)
                            val code = hex.toIntOrNull(16)
                            if (code != null) {
                                out.append(code.toChar())
                                i += 6
                                continue
                            }
                        }
                    }
                    else -> {
                        out.append(escaped)
                        i += 2
                        continue
                    }
                }
            }
            out.append(c)
            i += 1
        }
        return out.toString()
    }
}
