package org.stypox.dicio.io.session

/**
 * Production jack contract: canonical PlayMedia(SmartTube) → one PlayAuto request.
 *
 * Does not parse Vietnamese. The [query] is already the clean media entity from Phase 4.5.
 */
fun interface SmartTubePlayAutoPort {
    fun play(query: String): SmartTubeProductionJackResult
}

data class SmartTubeProductionJackResult(
    val query: String,
    val playAutoRequestCount: Int,
    val launched: Boolean,
    val launchCount: Int,
    val watchUrl: String?,
    val videoId: String?,
    val resolvedTitle: String?,
    val targetPackage: String?,
    val intentAction: String?,
    val failure: String?,
    val path: String,
    val youtubeFallbackUsed: Boolean,
    val accessibilityUsed: Boolean,
    val castApisUsed: Boolean,
    val mediaKeysSent: Boolean,
    val resolverStatus: String?,
)
