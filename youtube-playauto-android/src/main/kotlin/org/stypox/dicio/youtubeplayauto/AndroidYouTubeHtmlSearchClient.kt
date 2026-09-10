package org.stypox.dicio.youtubeplayauto

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Fetches the public YouTube results page. Not an official Data API.
 * No API key. Fails closed on empty/consent HTML.
 */
class AndroidYouTubeHtmlSearchClient(
    private val timeoutMs: Int = 8_000,
) : YouTubeQuerySearchClient {
    override fun search(query: String): YouTubeQuerySearchResult {
        val q = query.trim()
        if (q.isEmpty()) return YouTubeQuerySearchResult.Failed("blank_query")
        val encoded = URLEncoder.encode(q, StandardCharsets.UTF_8.name()).replace("+", "%20")
        val url = "https://www.youtube.com/results?search_query=$encoded"
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36",
                )
                setRequestProperty("Accept-Language", "vi,en;q=0.8")
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                return YouTubeQuerySearchResult.Failed("http_$code")
            }
            val html = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val hits = YouTubePublicSearchHtmlParser.parse(html)
            if (hits.isEmpty()) {
                YouTubeQuerySearchResult.Failed("public_search_no_video_ids")
            } else {
                YouTubeQuerySearchResult.Hits(hits)
            }
        } catch (t: Exception) {
            YouTubeQuerySearchResult.Failed("public_search_io:${t.javaClass.simpleName}")
        } finally {
            connection?.disconnect()
        }
    }
}
