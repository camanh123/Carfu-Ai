package org.stypox.dicio.youtubeplayauto

data class YouTubePlayAutoOptions(
    /** Isolated 4.8 search+click path. Default OFF — not the primary PlayAuto route. */
    val accessibilityFallbackEnabled: Boolean = false,
)
