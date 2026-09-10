package org.stypox.dicio.youtubeplayauto

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Configurable resolver base URL. No production hostname is hardcoded.
 */
object YouTubeResolverEndpoint {
    const val PREFS_NAME = "carfu_resolver"
    const val PREFS_KEY = "CARFU_RESOLVER_BASE_URL"
    const val RESOLVE_PATH = "/v1/youtube/resolve"

    fun normalizeBaseUrl(raw: String): String = raw.trim().trimEnd('/')

    fun isConfigured(raw: String): Boolean {
        val base = normalizeBaseUrl(raw)
        if (base.isEmpty()) return false
        return base.startsWith("https://", ignoreCase = true) ||
            base.startsWith("http://", ignoreCase = true)
    }

    fun isHttps(raw: String): Boolean =
        normalizeBaseUrl(raw).startsWith("https://", ignoreCase = true)

    fun isCleartextHttp(raw: String): Boolean =
        normalizeBaseUrl(raw).startsWith("http://", ignoreCase = true)

    fun encodeQuery(query: String): String =
        URLEncoder.encode(query, StandardCharsets.UTF_8.name()).replace("+", "%20")

    fun resolveUri(baseUrl: String, query: String, lang: String, region: String): URI {
        val base = normalizeBaseUrl(baseUrl)
        val encoded = encodeQuery(query)
        val langEnc = encodeQuery(lang)
        val regionEnc = encodeQuery(region)
        return URI("$base$RESOLVE_PATH?q=$encoded&lang=$langEnc&region=$regionEnc")
    }
}
