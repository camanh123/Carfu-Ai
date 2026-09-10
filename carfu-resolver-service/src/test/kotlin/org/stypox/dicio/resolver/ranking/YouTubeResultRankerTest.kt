package org.stypox.dicio.resolver.ranking

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.stypox.dicio.resolver.query.QueryNormalizer
import org.stypox.dicio.resolver.support.TestCandidates

class YouTubeResultRankerTest : StringSpec({
    fun pick(query: String, candidates: List<YouTubeCandidate>): YouTubeCandidate? =
        YouTubeResultRanker.pick(QueryNormalizer.normalize(query), candidates)?.candidate

    "exact title chooses the matching result" {
        val exact = YouTubeCandidate(
            videoId = "eXaCtItLe12",
            title = TestCandidates.QUERY,
            channelTitle = "Unknown",
        )
        pick(TestCandidates.QUERY, listOf(TestCandidates.unrelated, exact, TestCandidates.cover))
            ?.videoId shouldBe exact.videoId
    }

    "official MV beats remix when remix is not requested" {
        pick(
            TestCandidates.QUERY,
            listOf(TestCandidates.remix, TestCandidates.officialMv),
        )?.videoId shouldBe TestCandidates.officialMv.videoId
    }

    "karaoke is selected when karaoke is requested" {
        pick(
            "${TestCandidates.QUERY} karaoke",
            TestCandidates.allVariants(),
        )?.videoId shouldBe TestCandidates.karaoke.videoId
    }

    "remix is selected when remix is requested" {
        pick(
            "${TestCandidates.QUERY} remix",
            TestCandidates.allVariants(),
        )?.videoId shouldBe TestCandidates.remix.videoId
    }

    "artist tokens improve ranking" {
        val genericChannel = TestCandidates.officialMv.copy(
            videoId = "gEnErIcCh12",
            channelTitle = "Random Uploads",
        )
        val artistChannel = TestCandidates.officialMv.copy(
            videoId = "aRtIsTcH123",
            channelTitle = "Hồ Hoàng Yến Official",
        )
        pick(
            "Hồ Hoàng Yến ${TestCandidates.QUERY}",
            listOf(genericChannel, artistChannel),
        )?.videoId shouldBe artistChannel.videoId
    }

    "unrelated result is rejected" {
        pick(TestCandidates.QUERY, listOf(TestCandidates.unrelated)).shouldBeNull()
    }

    "no passing candidate yields null rather than items[0]" {
        val weak = YouTubeCandidate(
            videoId = "wEaKiTeM123",
            title = "totally different topic remix karaoke",
            channelTitle = "Spam",
        )
        YouTubeResultRanker.pick(
            QueryNormalizer.normalize(TestCandidates.QUERY),
            listOf(weak),
        ).shouldBeNull()
    }

    "shorts are preferred less than a normal official video" {
        val shorts = TestCandidates.officialMv.copy(
            videoId = "sHoRtSvId12",
            title = "Đừng Xa Em Đêm Nay #shorts",
        )
        pick(TestCandidates.QUERY, listOf(shorts, TestCandidates.officialMv))
            ?.videoId shouldBe TestCandidates.officialMv.videoId
    }

    "minimum score is required" {
        YouTubeResultRanker.score(
            QueryNormalizer.normalize(TestCandidates.QUERY),
            TestCandidates.unrelated,
        ).shouldBeNull()
        YouTubeResultRanker.score(
            QueryNormalizer.normalize(TestCandidates.QUERY),
            TestCandidates.officialMv,
        ).shouldNotBeNull()
    }
})
