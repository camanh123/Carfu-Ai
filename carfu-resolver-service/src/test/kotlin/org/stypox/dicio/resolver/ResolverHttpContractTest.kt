package org.stypox.dicio.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.stypox.dicio.resolver.service.YouTubeResolveService
import org.stypox.dicio.resolver.support.FakeYouTubeSearchProvider
import org.stypox.dicio.resolver.support.TestCandidates
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class ResolverHttpContractTest : StringSpec({
    val provider = FakeYouTubeSearchProvider.success(TestCandidates.allVariants())
    val config = ResolverConfig(apiKey = "", port = 0, fetchStats = false)
    val service = YouTubeResolveService(provider = provider, apiKeyConfigured = true)
    val server = ResolverHttpServer(config, service)
    val port = server.start(0)
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

    afterSpec { server.stop() }

    fun get(path: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:$port$path"))
            .GET()
            .timeout(Duration.ofSeconds(3))
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    "health and diagnostics never expose a key" {
        get("/health").body() shouldContain """"status":"ok""""
        val diagnostics = get("/v1/diagnostics").body()
        diagnostics shouldContain """"youtubeApiKeyConfigured":false"""
        diagnostics.shouldNotContain("AIza")
        diagnostics.shouldNotContain("YOUTUBE_API_KEY")
    }

    "resolve contract returns RESOLVED with watch URL" {
        val encoded = java.net.URLEncoder.encode(TestCandidates.QUERY, Charsets.UTF_8)
        val body = get("/v1/youtube/resolve?q=$encoded&lang=vi&region=VN").body()
        body shouldContain """"status":"RESOLVED""""
        body shouldContain """"videoId":"${TestCandidates.officialMv.videoId}""""
        body shouldContain """"source":"youtube_data_api_v3""""
        body shouldContain """"cache":"MISS""""
        body shouldContain "https://www.youtube.com/watch?v=${TestCandidates.officialMv.videoId}"
        get("/v1/youtube/resolve?q=$encoded&lang=vi&region=VN").body() shouldContain """"cache":"HIT""""
        provider.callCount shouldBe 1
    }

    "missing q is INVALID_RESPONSE" {
        get("/v1/youtube/resolve").body() shouldContain """"status":"INVALID_RESPONSE""""
    }
})
