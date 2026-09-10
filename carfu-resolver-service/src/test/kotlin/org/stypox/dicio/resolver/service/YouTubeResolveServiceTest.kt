package org.stypox.dicio.resolver.service

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.stypox.dicio.resolver.api.ResolveResponse
import org.stypox.dicio.resolver.api.ResolveStatus
import org.stypox.dicio.resolver.cache.InMemoryResolverCache
import org.stypox.dicio.resolver.support.FakeYouTubeSearchProvider
import org.stypox.dicio.resolver.support.TestCandidates
import org.stypox.dicio.resolver.youtube.YouTubeSearchOutcome

class YouTubeResolveServiceTest : StringSpec({
    fun service(
        provider: FakeYouTubeSearchProvider,
        apiKeyConfigured: Boolean = true,
    ) = YouTubeResolveService(
        provider = provider,
        cache = InMemoryResolverCache(),
        apiKeyConfigured = apiKeyConfigured,
    )

    "no results from provider" {
        val provider = FakeYouTubeSearchProvider.success()
        val response = service(provider).resolve(TestCandidates.QUERY, "vi", "VN")
        response.status shouldBe ResolveStatus.NO_RESULTS
        response.cache shouldBe ResolveResponse.CACHE_MISS
        response.videoId shouldBe null
    }

    "unrelated candidates become NO_RESULTS rather than a fallback video" {
        val provider = FakeYouTubeSearchProvider.success(TestCandidates.unrelated)
        val response = service(provider).resolve(TestCandidates.QUERY)
        response.status shouldBe ResolveStatus.NO_RESULTS
        response.videoId shouldBe null
    }

    "invalid upstream JSON mapping" {
        val provider = FakeYouTubeSearchProvider.failure(
            YouTubeSearchOutcome.Failure(ResolveStatus.INVALID_RESPONSE, "malformed_json"),
        )
        service(provider).resolve(TestCandidates.QUERY).status shouldBe ResolveStatus.INVALID_RESPONSE
    }

    "quota exceeded" {
        val provider = FakeYouTubeSearchProvider.failure(
            YouTubeSearchOutcome.Failure(ResolveStatus.QUOTA_EXCEEDED, "http_403"),
        )
        service(provider).resolve(TestCandidates.QUERY).status shouldBe ResolveStatus.QUOTA_EXCEEDED
    }

    "timeout" {
        val provider = FakeYouTubeSearchProvider.failure(
            YouTubeSearchOutcome.Failure(ResolveStatus.TIMEOUT, "timeout"),
        )
        service(provider).resolve(TestCandidates.QUERY).status shouldBe ResolveStatus.TIMEOUT
    }

    "API / network failure" {
        val provider = FakeYouTubeSearchProvider.failure(
            YouTubeSearchOutcome.Failure(ResolveStatus.RESOLVER_UNAVAILABLE, "network_failure"),
        )
        service(provider).resolve(TestCandidates.QUERY).status shouldBe ResolveStatus.RESOLVER_UNAVAILABLE
    }

    "missing API key is a clear configuration error" {
        val provider = FakeYouTubeSearchProvider { error("provider must not be called") }
        val response = service(provider, apiKeyConfigured = false).resolve(TestCandidates.QUERY)
        response.status shouldBe ResolveStatus.RESOLVER_UNAVAILABLE
        response.error shouldBe ResolveResponse.ERROR_MISSING_API_KEY
        provider.callCount shouldBe 0
    }

    "cache MISS then HIT does not call YouTube again" {
        val provider = FakeYouTubeSearchProvider.success(TestCandidates.allVariants())
        val resolver = service(provider)
        val miss = resolver.resolve(TestCandidates.QUERY, "vi", "VN")
        miss.status shouldBe ResolveStatus.RESOLVED
        miss.cache shouldBe ResolveResponse.CACHE_MISS
        miss.videoId shouldBe TestCandidates.officialMv.videoId
        provider.callCount shouldBe 1

        val hit = resolver.resolve("  đừng xa em đêm nay  ", "vi", "VN")
        hit.status shouldBe ResolveStatus.RESOLVED
        hit.cache shouldBe ResolveResponse.CACHE_HIT
        hit.videoId shouldBe TestCandidates.officialMv.videoId
        hit.watchUrl shouldBe "https://www.youtube.com/watch?v=${TestCandidates.officialMv.videoId}"
        provider.callCount shouldBe 1
    }

    "cache separates variant flags" {
        val provider = FakeYouTubeSearchProvider.success(TestCandidates.allVariants())
        val resolver = service(provider)
        resolver.resolve(TestCandidates.QUERY).videoId shouldBe TestCandidates.officialMv.videoId
        resolver.resolve("${TestCandidates.QUERY} karaoke").videoId shouldBe TestCandidates.karaoke.videoId
        provider.callCount shouldBe 2
    }

    "empty query is INVALID_RESPONSE" {
        val provider = FakeYouTubeSearchProvider.success(TestCandidates.officialMv)
        service(provider).resolve("   ").status shouldBe ResolveStatus.INVALID_RESPONSE
        provider.callCount shouldBe 0
    }

    "watch URL is generated from the selected videoId" {
        val provider = FakeYouTubeSearchProvider.success(TestCandidates.officialMv)
        val response = service(provider).resolve(TestCandidates.QUERY)
        response.watchUrl shouldBe "https://www.youtube.com/watch?v=${TestCandidates.officialMv.videoId}"
        response.source shouldBe ResolveResponse.SOURCE_YOUTUBE_DATA_API_V3
        response.query shouldBe TestCandidates.QUERY
    }
})
