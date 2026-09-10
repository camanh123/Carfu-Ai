package org.stypox.dicio.resolver.api

data class ResolveResponse(
    val status: ResolveStatus,
    val query: String? = null,
    val videoId: String? = null,
    val title: String? = null,
    val channelTitle: String? = null,
    val watchUrl: String? = null,
    val source: String? = null,
    val cache: String? = null,
    val error: String? = null,
) {
    fun toJson(): String = JsonObjectWriter.write(
        "status" to status.name,
        "query" to query,
        "videoId" to videoId,
        "title" to title,
        "channelTitle" to channelTitle,
        "watchUrl" to watchUrl,
        "source" to source,
        "cache" to cache,
        "error" to error,
    )

    companion object {
        const val SOURCE_YOUTUBE_DATA_API_V3 = "youtube_data_api_v3"
        const val CACHE_HIT = "HIT"
        const val CACHE_MISS = "MISS"
        const val ERROR_MISSING_API_KEY = "MISSING_API_KEY"
    }
}

internal object JsonObjectWriter {
    fun write(vararg fields: Pair<String, String?>): String {
        val body = fields
            .filter { it.second != null }
            .joinToString(",") { (key, value) ->
                "\"${escape(key)}\":\"${escape(value!!)}\""
            }
        return "{$body}"
    }

    private fun escape(value: String): String {
        val out = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch.code < 0x20) {
                    out.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(ch)
                }
            }
        }
        return out.toString()
    }
}
