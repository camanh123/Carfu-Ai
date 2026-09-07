package org.stypox.dicio.io.session

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Builds Maps navigation payloads from a destination entity — never from raw STT.
 */
object NavigatePayload {
    /**
     * `geo:0,0?q=<encoded destination>` for Google Maps / geo handlers.
     * User-facing destination text is preserved; only the URI transport is encoded.
     */
    fun geoUri(destination: String): String {
        val q = destination.trim()
        require(q.isNotEmpty()) { "destination required" }
        val encoded = URLEncoder.encode(q, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
        return "geo:0,0?q=$encoded"
    }

    /** Decode the `q` parameter after encoding (for regression tests). */
    fun decodeQuery(geoUri: String): String? {
        val marker = "q="
        val idx = geoUri.indexOf(marker)
        if (idx < 0) return null
        val encoded = geoUri.substring(idx + marker.length).substringBefore('&')
        return URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
    }
}
