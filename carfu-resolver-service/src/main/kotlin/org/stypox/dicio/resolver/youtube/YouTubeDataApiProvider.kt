package org.stypox.dicio.resolver.youtube

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.logging.Logger

class YouTubeDataApiProvider(
    private val apiKey: String,
    private val http: HttpGetClient = JdkHttpGetClient(),
) : YouTubeSearchProvider {
    override fun search(request: YouTubeSearchRequest): YouTubeSearchOutcome {
        if (apiKey.isBlank()) {
            return YouTubeSearchOutcome.Failure(
                org.stypox.dicio.resolver.api.ResolveStatus.RESOLVER_UNAVAILABLE,
                ResolveStatusDetail.MISSING_API_KEY,
            )
        }
        val uri = buildSearchUri(request, apiKey)
        LOGGER.info {
            "youtube search.list type=video maxResults=${request.maxResults} " +
                "lang=${request.lang} region=${request.region} q_len=${request.query.length}"
        }
        val httpResult = try {
            http.get(uri)
        } catch (error: Exception) {
            return mapTransportFailure(error)
        }
        if (httpResult.code != 200) {
            return YouTubeApiResponseParser.mapHttpError(httpResult.code, httpResult.body)
        }
        return YouTubeApiResponseParser.parseSearchList(httpResult.body)
    }

    companion object {
        private val LOGGER = Logger.getLogger(YouTubeDataApiProvider::class.java.name)

        internal fun buildSearchUri(request: YouTubeSearchRequest, apiKey: String): URI {
            val params = linkedMapOf(
                "part" to "snippet",
                "type" to "video",
                "maxResults" to request.maxResults.coerceIn(1, 10).toString(),
                "q" to request.query,
            )
            if (request.lang.isNotBlank()) {
                params["relevanceLanguage"] = request.lang
            }
            if (request.region.isNotBlank()) {
                params["regionCode"] = request.region
            }
            params["key"] = apiKey
            val query = params.entries.joinToString("&") { (name, value) ->
                "${enc(name)}=${enc(value)}"
            }
            return URI("https://www.googleapis.com/youtube/v3/search?$query")
        }

        private fun enc(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8)
    }
}

internal object ResolveStatusDetail {
    const val MISSING_API_KEY = "MISSING_API_KEY"
}
