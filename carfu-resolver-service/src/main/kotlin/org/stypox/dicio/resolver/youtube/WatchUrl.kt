package org.stypox.dicio.resolver.youtube

object WatchUrl {
    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

    fun isValidVideoId(videoId: String): Boolean = VIDEO_ID.matches(videoId)

    fun fromVideoId(videoId: String): String {
        require(isValidVideoId(videoId)) { "invalid videoId" }
        return "https://www.youtube.com/watch?v=$videoId"
    }
}
