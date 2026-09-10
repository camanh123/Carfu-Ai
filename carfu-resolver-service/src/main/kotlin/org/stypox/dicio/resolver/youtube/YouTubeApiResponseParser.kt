package org.stypox.dicio.resolver.youtube

import org.stypox.dicio.resolver.api.ResolveStatus
import org.stypox.dicio.resolver.ranking.YouTubeCandidate

internal object YouTubeApiResponseParser {
    fun parseSearchList(body: String): YouTubeSearchOutcome {
        val root = try {
            MiniJsonParser(body).parse()
        } catch (_: Exception) {
            return YouTubeSearchOutcome.Failure(
                ResolveStatus.INVALID_RESPONSE,
                "malformed_json",
            )
        }
        val items = root.asObj()["items"]
            ?: return YouTubeSearchOutcome.Failure(
                ResolveStatus.INVALID_RESPONSE,
                "missing_items",
            )
        if (items !is JsonValue.Arr) {
            return YouTubeSearchOutcome.Failure(
                ResolveStatus.INVALID_RESPONSE,
                "items_not_array",
            )
        }
        if (items.items.isEmpty()) {
            return YouTubeSearchOutcome.Success(emptyList())
        }
        val candidates = mutableListOf<YouTubeCandidate>()
        var sawItemWithoutId = false
        for (item in items.items) {
            val obj = item.asObj()
            val videoId = obj["id"]?.asObj()?.get("videoId")?.asString()
            if (videoId == null || !WatchUrl.isValidVideoId(videoId)) {
                sawItemWithoutId = true
                continue
            }
            val snippet = obj["snippet"]?.asObj() ?: emptyMap()
            val title = snippet["title"]?.asString().orEmpty()
            val channelTitle = snippet["channelTitle"]?.asString().orEmpty()
            val description = snippet["description"]?.asString().orEmpty()
            candidates.add(
                YouTubeCandidate(
                    videoId = videoId,
                    title = title,
                    channelTitle = channelTitle,
                    description = description,
                ),
            )
        }
        if (candidates.isEmpty() && sawItemWithoutId) {
            return YouTubeSearchOutcome.Failure(
                ResolveStatus.INVALID_RESPONSE,
                "missing_video_id",
            )
        }
        return YouTubeSearchOutcome.Success(candidates)
    }

    fun mapHttpError(httpCode: Int, body: String): YouTubeSearchOutcome.Failure {
        val reason = extractErrorReason(body)
        val status = when {
            httpCode == 403 && isQuotaReason(reason) -> ResolveStatus.QUOTA_EXCEEDED
            httpCode in 500..599 -> ResolveStatus.RESOLVER_UNAVAILABLE
            httpCode == 408 || httpCode == 504 -> ResolveStatus.TIMEOUT
            httpCode == 400 -> ResolveStatus.INVALID_RESPONSE
            else -> ResolveStatus.RESOLVER_UNAVAILABLE
        }
        return YouTubeSearchOutcome.Failure(status, "http_$httpCode")
    }

    private fun isQuotaReason(reason: String?): Boolean =
        reason == "quotaExceeded" || reason == "dailyLimitExceeded"

    private fun extractErrorReason(body: String): String? {
        return try {
            val errors = MiniJsonParser(body).parse()
                .asObj()["error"]
                ?.asObj()
                ?.get("errors")
                ?.asArr()
                ?: return null
            errors.firstOrNull()?.asObj()?.get("reason")?.asString()
        } catch (_: Exception) {
            null
        }
    }
}
