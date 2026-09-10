package org.stypox.dicio.resolver.support

import org.stypox.dicio.resolver.ranking.YouTubeCandidate
import org.stypox.dicio.resolver.youtube.YouTubeSearchOutcome
import org.stypox.dicio.resolver.youtube.YouTubeSearchProvider
import org.stypox.dicio.resolver.youtube.YouTubeSearchRequest

class FakeYouTubeSearchProvider(
    private val handler: (YouTubeSearchRequest) -> YouTubeSearchOutcome,
) : YouTubeSearchProvider {
    val requests = mutableListOf<YouTubeSearchRequest>()

    override fun search(request: YouTubeSearchRequest): YouTubeSearchOutcome {
        requests.add(request)
        return handler(request)
    }

    val callCount: Int get() = requests.size

    companion object {
        fun success(vararg candidates: YouTubeCandidate) =
            FakeYouTubeSearchProvider { YouTubeSearchOutcome.Success(candidates.toList()) }

        fun success(candidates: List<YouTubeCandidate>) =
            FakeYouTubeSearchProvider { YouTubeSearchOutcome.Success(candidates) }

        fun failure(outcome: YouTubeSearchOutcome.Failure) =
            FakeYouTubeSearchProvider { outcome }
    }
}

object TestCandidates {
    const val QUERY = "Đừng Xa Em Đêm Nay"

    val officialMv = YouTubeCandidate(
        videoId = "aBcDeFgHiJk",
        title = "Đừng Xa Em Đêm Nay - Hồ Hoàng Yến [Official 4K MV]",
        channelTitle = "Hồ Hoàng Yến Official",
        viewCount = 12_000_000,
    )
    val karaoke = YouTubeCandidate(
        videoId = "kArAoKeFixt",
        title = "Đừng Xa Em Đêm Nay | KARAOKE",
        channelTitle = "Karaoke Hits",
    )
    val remix = YouTubeCandidate(
        videoId = "rEmIxFixt12",
        title = "Đừng Xa Em Đêm Nay Remix",
        channelTitle = "DJ Mix Channel",
    )
    val cover = YouTubeCandidate(
        videoId = "cOvErFixt12",
        title = "Đừng Xa Em Đêm Nay Cover",
        channelTitle = "Cover Studio",
    )
    val live = YouTubeCandidate(
        videoId = "lIvEfIxt123",
        title = "Đừng Xa Em Đêm Nay Live",
        channelTitle = "Live Stage",
    )
    val unrelated = YouTubeCandidate(
        videoId = "uNrElAtEd12",
        title = "How to cook pho at home",
        channelTitle = "Cooking Daily",
    )

    fun allVariants(): List<YouTubeCandidate> =
        listOf(officialMv, karaoke, remix, cover, live)
}
