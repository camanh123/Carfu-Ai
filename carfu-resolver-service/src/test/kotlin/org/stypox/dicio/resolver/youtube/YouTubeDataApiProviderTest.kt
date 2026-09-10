package org.stypox.dicio.resolver.youtube

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.stypox.dicio.resolver.api.ResolveStatus
import org.stypox.dicio.resolver.ranking.YouTubeCandidate
import java.net.SocketTimeoutException
import java.net.URI

class YouTubeDataApiProviderTest : StringSpec({
    val request = YouTubeSearchRequest("sample query", "vi", "VN", maxResults = 8)

    "search.list URI uses official endpoint and never embeds a test song" {
        val uri = YouTubeDataApiProvider.buildSearchUri(request, "dummy-key")
        uri.host shouldBe "www.googleapis.com"
        uri.path shouldBe "/youtube/v3/search"
        uri.query shouldBe "part=snippet&type=video&maxResults=8&q=sample+query&relevanceLanguage=vi&regionCode=VN&key=dummy-key"
        uri.toString().shouldNotContain("Đừng")
        uri.toString().lowercase().shouldNotContain("karaoke")
    }

    "parses search.list items" {
        val body = """
            {"items":[
              {"id":{"videoId":"aBcDeFgHiJk"},"snippet":{"title":"One","channelTitle":"Ch"}}
            ]}
        """.trimIndent()
        val outcome = YouTubeApiResponseParser.parseSearchList(body)
            .shouldBeInstanceOf<YouTubeSearchOutcome.Success>()
        outcome.candidates shouldBe listOf(
            YouTubeCandidate("aBcDeFgHiJk", "One", "Ch"),
        )
    }

    "empty items is success with no candidates" {
        val outcome = YouTubeApiResponseParser.parseSearchList("""{"items":[]}""")
            .shouldBeInstanceOf<YouTubeSearchOutcome.Success>()
        outcome.candidates shouldBe emptyList()
    }

    "missing videoId is INVALID_RESPONSE" {
        val outcome = YouTubeApiResponseParser.parseSearchList(
            """{"items":[{"id":{"kind":"youtube#video"},"snippet":{"title":"X"}}]}""",
        ).shouldBeInstanceOf<YouTubeSearchOutcome.Failure>()
        outcome.status shouldBe ResolveStatus.INVALID_RESPONSE
    }

    "malformed JSON is INVALID_RESPONSE" {
        val outcome = YouTubeApiResponseParser.parseSearchList("not-json")
            .shouldBeInstanceOf<YouTubeSearchOutcome.Failure>()
        outcome.status shouldBe ResolveStatus.INVALID_RESPONSE
    }

    "403 quotaExceeded maps to QUOTA_EXCEEDED" {
        val body = """{"error":{"errors":[{"reason":"quotaExceeded"}]}}"""
        YouTubeApiResponseParser.mapHttpError(403, body).status shouldBe ResolveStatus.QUOTA_EXCEEDED
    }

    "upstream 5xx maps to RESOLVER_UNAVAILABLE" {
        YouTubeApiResponseParser.mapHttpError(503, "{}").status shouldBe
            ResolveStatus.RESOLVER_UNAVAILABLE
    }

    "provider maps timeout and network failures" {
        val timeoutProvider = YouTubeDataApiProvider("key") {
            throw SocketTimeoutException("read timed out")
        }
        timeoutProvider.search(request)
            .shouldBeInstanceOf<YouTubeSearchOutcome.Failure>()
            .status shouldBe ResolveStatus.TIMEOUT

        val networkProvider = YouTubeDataApiProvider("key") {
            throw java.io.IOException("connection reset")
        }
        networkProvider.search(request)
            .shouldBeInstanceOf<YouTubeSearchOutcome.Failure>()
            .status shouldBe ResolveStatus.RESOLVER_UNAVAILABLE
    }

    "blank API key does not call HTTP" {
        var called = false
        val provider = YouTubeDataApiProvider(" ") { called = true; error("no") }
        val outcome = provider.search(request).shouldBeInstanceOf<YouTubeSearchOutcome.Failure>()
        outcome.status shouldBe ResolveStatus.RESOLVER_UNAVAILABLE
        outcome.detail shouldBe ResolveStatusDetail.MISSING_API_KEY
        called shouldBe false
    }

    "HTTP client receives the official search URI" {
        var seen: URI? = null
        val provider = YouTubeDataApiProvider("env-key-only") { uri ->
            seen = uri
            HttpGetResult(200, """{"items":[]}""")
        }
        provider.search(request)
        seen!!.host shouldBe "www.googleapis.com"
        seen!!.path shouldBe "/youtube/v3/search"
        seen!!.rawQuery.contains("type=video") shouldBe true
        seen!!.rawQuery.contains("key=env-key-only") shouldBe true
    }
})
