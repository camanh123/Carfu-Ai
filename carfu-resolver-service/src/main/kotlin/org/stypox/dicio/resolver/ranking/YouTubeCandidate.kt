package org.stypox.dicio.resolver.ranking

data class YouTubeCandidate(
    val videoId: String,
    val title: String,
    val channelTitle: String,
    val description: String = "",
    val viewCount: Long? = null,
    val durationSeconds: Long? = null,
)
