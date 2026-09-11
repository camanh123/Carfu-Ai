package org.stypox.dicio.io.session

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Builds Maps **navigation** payloads from a destination entity — never from raw STT.
 *
 * Production Navigate uses `google.navigation:q=` (route-ready Google Maps).
 * `geo:0,0?q=` is a place-search URI and is not the production Navigate path.
 */
object NavigatePayload {
    const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"
    const val NAVIGATION_PREFIX = "google.navigation:q="
    const val GEO_SEARCH_PREFIX = "geo:0,0?q="

    /**
     * Direct Google Maps navigation URI. Null when [destination] is blank.
     */
    fun navigationUri(destination: String): String? {
        val q = destination.trim()
        if (q.isEmpty()) return null
        return NAVIGATION_PREFIX + encode(q)
    }

    /**
     * Legacy geo search URI. Not used by CanonicalCommand.Navigate.
     */
    fun geoUri(destination: String): String {
        val q = destination.trim()
        require(q.isNotEmpty()) { "destination required" }
        return GEO_SEARCH_PREFIX + encode(q)
    }

    fun isNavigationUri(uri: String?): Boolean =
        !uri.isNullOrBlank() && uri.startsWith(NAVIGATION_PREFIX)

    fun isGeoSearchUri(uri: String?): Boolean =
        !uri.isNullOrBlank() && uri.startsWith(GEO_SEARCH_PREFIX)

    /** Decode the `q` parameter after encoding (for regression tests). */
    fun decodeQuery(uri: String): String? {
        val marker = "q="
        val idx = uri.indexOf(marker)
        if (idx < 0) return null
        val encoded = uri.substring(idx + marker.length).substringBefore('&')
        return URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
}
