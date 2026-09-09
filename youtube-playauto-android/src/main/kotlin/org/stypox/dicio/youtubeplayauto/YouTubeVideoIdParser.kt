package org.stypox.dicio.youtubeplayauto

/**
 * Accepts a tester-supplied id or watch URL. Does not derive ids from titles.
 */
object YouTubeVideoIdParser {
    private val ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val WATCH = Regex("(?:v=|/embed/|/shorts/|youtu\\.be/)([A-Za-z0-9_-]{11})")

    fun parse(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (ID.matches(trimmed)) return trimmed
        return WATCH.find(trimmed)?.groupValues?.getOrNull(1)
    }

    fun canonicalWatchUri(videoId: String): String =
        "https://www.youtube.com/watch?v=$videoId"
}
