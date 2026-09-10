package org.stypox.dicio.youtubeplayauto

/**
 * Tiny REST-contract parser for GET /v1/youtube/resolve.
 * Rejects malformed JSON instead of launching a fallback video.
 */
object YouTubeResolverJson {
    fun parse(httpStatus: Int, body: String, metaBase: YouTubeResolverMeta): YouTubeResolveResult {
        val fields = parseObject(body) ?: return YouTubeResolveResult.InvalidResponse(
            metaBase.copy(httpStatus = httpStatus),
        )
        val status = fields["status"] ?: return YouTubeResolveResult.InvalidResponse(
            metaBase.copy(httpStatus = httpStatus),
        )
        val meta = metaBase.copy(httpStatus = httpStatus)
        return when (status) {
            "RESOLVED" -> parseResolved(fields, meta)
            "NO_RESULTS" -> YouTubeResolveResult.NoResults(meta)
            "QUOTA_EXCEEDED" -> YouTubeResolveResult.QuotaExceeded(meta)
            "RESOLVER_UNAVAILABLE" -> YouTubeResolveResult.ResolverUnavailable(meta)
            "INVALID_RESPONSE" -> YouTubeResolveResult.InvalidResponse(meta)
            "TIMEOUT" -> YouTubeResolveResult.Timeout(meta)
            "NETWORK_UNAVAILABLE" -> YouTubeResolveResult.NetworkUnavailable(meta)
            else -> YouTubeResolveResult.InvalidResponse(meta)
        }
    }

    fun parseObject(body: String): Map<String, String>? {
        val trimmed = body.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return null
        }
        val out = linkedMapOf<String, String>()
        val regex = Regex(""""([A-Za-z0-9_]+)"\s*:\s*"((?:\\.|[^"\\])*)"""")
        for (match in regex.findAll(trimmed)) {
            out[match.groupValues[1]] = unescape(match.groupValues[2])
        }
        if (out.isEmpty() && trimmed.contains("\"status\"")) {
            return null
        }
        return out
    }

    private fun parseResolved(
        fields: Map<String, String>,
        meta: YouTubeResolverMeta,
    ): YouTubeResolveResult {
        val videoId = fields["videoId"]
        val parsedId = videoId?.let { YouTubeVideoIdParser.parse(it) }
        if (parsedId == null || parsedId != videoId) {
            return YouTubeResolveResult.InvalidResponse(meta)
        }
        val watchUrl = fields["watchUrl"]
        val parsedWatch = watchUrl?.let { YouTubeVideoIdParser.parse(it) }
        val canonical = YouTubeVideoIdParser.canonicalWatchUri(parsedId)
        val preserved = when {
            watchUrl.isNullOrBlank() -> canonical
            parsedWatch == parsedId && watchUrl == canonical -> watchUrl
            parsedWatch == parsedId && watchUrl.startsWith(YouTubePublicLaunchAudit.WATCH_HOST_PATH) ->
                watchUrl
            else -> return YouTubeResolveResult.InvalidResponse(meta)
        }
        val cache = fields["cache"]?.takeIf { it == "HIT" || it == "MISS" }
        return YouTubeResolveResult.Resolved(
            videoId = parsedId,
            title = fields["title"],
            channelTitle = fields["channelTitle"],
            watchUrl = preserved,
            cache = cache,
            meta = meta,
        )
    }

    private fun unescape(value: String): String {
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '\\' && i + 1 < value.length) {
                when (val next = value[i + 1]) {
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    '"', '\\' -> out.append(next)
                    'u' -> {
                        if (i + 5 < value.length) {
                            val hex = value.substring(i + 2, i + 6)
                            val code = hex.toIntOrNull(16)
                            if (code != null) {
                                out.append(code.toChar())
                                i += 6
                                continue
                            }
                        }
                        out.append(next)
                    }
                    else -> out.append(next)
                }
                i += 2
            } else {
                out.append(ch)
                i += 1
            }
        }
        return out.toString()
    }
}
