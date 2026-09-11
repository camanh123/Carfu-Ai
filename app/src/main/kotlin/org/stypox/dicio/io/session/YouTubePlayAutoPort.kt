package org.stypox.dicio.io.session

/**
 * Production jack contract: canonical PlayMedia(YouTube) → one PlayAuto request.
 *
 * Does not parse Vietnamese. The [query] is already the clean media entity from Phase 4.5.
 */
fun interface YouTubePlayAutoPort {
    fun play(query: String): YouTubeProductionJackResult
}

data class YouTubeProductionJackResult(
    val query: String,
    val playAutoRequestCount: Int,
    val launched: Boolean,
    val launchCount: Int,
    val watchUrl: String?,
    val videoId: String?,
    val failure: String?,
    val path: String,
    val searchOpened: Boolean,
    val accessibilityFallbackUsed: Boolean,
    val castApisUsed: Boolean,
    val mediaKeysSent: Boolean,
    val resolverStatus: String?,
    val resolvedTitle: String? = null,
)
