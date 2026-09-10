package org.stypox.dicio.resolver.youtube

import org.stypox.dicio.resolver.api.ResolveStatus
import org.stypox.dicio.resolver.ranking.YouTubeCandidate

data class YouTubeSearchRequest(
    val query: String,
    val lang: String,
    val region: String,
    val maxResults: Int = 8,
)

sealed class YouTubeSearchOutcome {
    data class Success(val candidates: List<YouTubeCandidate>) : YouTubeSearchOutcome()
    data class Failure(val status: ResolveStatus, val detail: String) : YouTubeSearchOutcome()
}

fun interface YouTubeSearchProvider {
    fun search(request: YouTubeSearchRequest): YouTubeSearchOutcome
}
