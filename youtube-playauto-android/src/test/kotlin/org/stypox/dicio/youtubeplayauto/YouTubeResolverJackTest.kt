package org.stypox.dicio.youtubeplayauto

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.stypox.dicio.playauto.core.PlaybackStrategy
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException

class YouTubeResolverJackTest : StringSpec({
    val song = "Đừng Xa Em Đêm Nay"
    val videoId = "AbCdeFghi_K"
    val watch = YouTubeVideoIdParser.canonicalWatchUri(videoId)
    val title = "$song - Hồ Hoàng Yến [Official 4K MV]"
    val channel = "MMG Studios"

    fun resolved(
        cache: String = "MISS",
        watchUrl: String = watch,
        id: String = videoId,
    ) = YouTubeResolveResult.Resolved(
        videoId = id,
        title = title,
        channelTitle = channel,
        watchUrl = watchUrl,
        cache = cache,
        meta = YouTubeResolverMeta(
            httpStatus = 200,
            latencyMs = 17,
            requestAttempted = true,
            baseUrlConfigured = true,
            usedHttps = true,
        ),
    )

    fun client(result: YouTubeResolveResult) = FakeYouTubeResolverClient(result = result)

    fun driver(
        client: FakeYouTubeResolverClient,
        runtime: FakeYouTubeRuntime = FakeYouTubeRuntime(),
        selector: FakeYouTubeInAppSelector = FakeYouTubeInAppSelector(),
        options: YouTubePlayAutoOptions = YouTubePlayAutoOptions(),
        content: YouTubeContentResolver = NoOpYouTubeContentResolver,
    ): Triple<YouTubePlayAutoDriver, YouTubeMediaAdapter, FakeYouTubeInAppSelector> {
        val adapter = YouTubeMediaAdapter(runtime, launchMode = YouTubeLaunchMode.DEVICE_TEST).also { it.detect() }
        return Triple(
            YouTubePlayAutoDriver(
                adapter = adapter,
                selector = selector,
                resolver = content,
                options = options,
                resolverClient = client,
            ),
            adapter,
            selector,
        )
    }

    "RESOLVED causes exactly one direct launch" {
        val fake = client(resolved())
        val (playAuto, adapter, selector) = driver(fake)
        val result = playAuto.execute(PlayAutoRequest("YouTube", song), YouTubeLaunchMode.DEVICE_TEST)
        result.resolverSuccess shouldBe true
        result.resolverStatus shouldBe "RESOLVED"
        result.resolvedVideoId shouldBe videoId
        result.resolvedTitle shouldBe title
        result.resolvedChannelTitle shouldBe channel
        result.targetUri shouldBe watch
        result.resolverCache shouldBe "MISS"
        result.path shouldBe "RESOLVER_DIRECT_TARGET"
        result.launchAttempted shouldBe true
        result.accessibilityFallbackUsed shouldBe false
        result.searchOpened shouldBe false
        selector.selectCount shouldBe 0
        adapter.dispatchCount shouldBe 1
        adapter.lastStrategy shouldBe PlaybackStrategy.DEEP_LINK
        adapter.lastSpec?.action shouldBe YouTubeLaunchSpec.ACTION_VIEW
        adapter.lastSpec?.uri shouldBe watch
        adapter.lastSpec?.packageName shouldBe YouTubeCandidatePackages.OFFICIAL
        fake.resolveCount shouldBe 1
    }

    "NO_RESULTS launches zero times" {
        val fake = client(YouTubeResolveResult.NoResults(YouTubeResolverMeta(requestAttempted = true, baseUrlConfigured = true, httpStatus = 200)))
        val (playAuto, adapter, selector) = driver(fake)
        val result = playAuto.execute(PlayAutoRequest("YouTube", song), YouTubeLaunchMode.DEVICE_TEST)
        result.failure shouldBe "NO_RESULTS"
        result.launchAttempted shouldBe false
        adapter.dispatchCount shouldBe 0
        selector.selectCount shouldBe 0
        fake.resolveCount shouldBe 1
        result.path shouldBe "RESOLVER_DIRECT_TARGET"
    }

    "NETWORK_UNAVAILABLE launches zero times" {
        val fake = client(YouTubeResolveResult.NetworkUnavailable(YouTubeResolverMeta(requestAttempted = true, baseUrlConfigured = true)))
        val (playAuto, adapter, _) = driver(fake)
        playAuto.execute(PlayAutoRequest("YouTube", song), YouTubeLaunchMode.DEVICE_TEST)
            .launchAttempted shouldBe false
        adapter.dispatchCount shouldBe 0
    }

    "RESOLVER_UNAVAILABLE launches zero times" {
        val fake = client(YouTubeResolveResult.ResolverUnavailable())
        val (playAuto, adapter, _) = driver(fake)
        val result = playAuto.execute(PlayAutoRequest("YouTube", song), YouTubeLaunchMode.DEVICE_TEST)
        result.failure shouldBe "RESOLVER_UNAVAILABLE"
        adapter.dispatchCount shouldBe 0
    }

    "QUOTA_EXCEEDED launches zero times" {
        val fake = client(YouTubeResolveResult.QuotaExceeded(YouTubeResolverMeta(httpStatus = 200, requestAttempted = true, baseUrlConfigured = true)))
        val (playAuto, adapter, _) = driver(fake)
        playAuto.execute(PlayAutoRequest("YouTube", song), YouTubeLaunchMode.DEVICE_TEST)
            .failure shouldBe "QUOTA_EXCEEDED"
        adapter.dispatchCount shouldBe 0
    }

    "INVALID_RESPONSE launches zero times" {
        val fake = client(YouTubeResolveResult.InvalidResponse(YouTubeResolverMeta(requestAttempted = true, baseUrlConfigured = true, httpStatus = 200)))
        val (playAuto, adapter, _) = driver(fake)
        playAuto.execute(PlayAutoRequest("YouTube", song), YouTubeLaunchMode.DEVICE_TEST)
            .failure shouldBe "INVALID_RESPONSE"
        adapter.dispatchCount shouldBe 0
    }

    "TIMEOUT launches zero times" {
        val fake = client(YouTubeResolveResult.Timeout(YouTubeResolverMeta(requestAttempted = true, baseUrlConfigured = true)))
        val (playAuto, adapter, _) = driver(fake)
        playAuto.execute(PlayAutoRequest("YouTube", song), YouTubeLaunchMode.DEVICE_TEST)
            .failure shouldBe "TIMEOUT"
        adapter.dispatchCount shouldBe 0
    }

    "malformed JSON is INVALID_RESPONSE and does not launch" {
        val http = HttpYouTubeResolverClient(
            baseUrlProvider = { "https://resolver.test" },
            transport = { ResolverHttpResponse(200, "not-json") },
        )
        runBlocking { http.resolve(song) }.shouldBeInstanceOf<YouTubeResolveResult.InvalidResponse>()
        val (playAuto, adapter, _) = driver(client(YouTubeResolveResult.InvalidResponse()))
        playAuto.execute(PlayAutoRequest("YouTube", song), YouTubeLaunchMode.DEVICE_TEST)
        adapter.dispatchCount shouldBe 0
    }

    "duplicate callback cannot double-launch or re-query" {
        val fake = client(resolved())
        val (playAuto, adapter, selector) = driver(fake)
        val first = playAuto.execute(PlayAutoRequest("YouTube", song), YouTubeLaunchMode.DEVICE_TEST)
        val second = playAuto.execute(PlayAutoRequest("YouTube", song), YouTubeLaunchMode.DEVICE_TEST)
        first.launchAttempted shouldBe true
        second.resolvedVideoId shouldBe videoId
        fake.resolveCount shouldBe 1
        adapter.dispatchCount shouldBe 1
        adapter.dispatchCount shouldBeLessThanOrEqual 1
        selector.selectCount shouldBe 0
    }

    "resolved watchUrl is preserved" {
        val fake = client(resolved(watchUrl = watch))
        val (playAuto, adapter, _) = driver(fake)
        val result = playAuto.execute(PlayAutoRequest("YouTube", song), YouTubeLaunchMode.DEVICE_TEST)
        result.targetUri shouldBe watch
        adapter.lastSpec?.uri shouldBe watch
        result.targetUri shouldBe "https://www.youtube.com/watch?v=$videoId"
    }

    "cache HIT and MISS are parsed" {
        YouTubeResolverJson.parse(
            200,
            """{"status":"RESOLVED","videoId":"$videoId","title":"$title","channelTitle":"$channel","watchUrl":"$watch","cache":"HIT"}""",
            YouTubeResolverMeta(requestAttempted = true, baseUrlConfigured = true),
        ).shouldBeInstanceOf<YouTubeResolveResult.Resolved>().cache shouldBe "HIT"
        YouTubeResolverJson.parse(
            200,
            """{"status":"RESOLVED","videoId":"$videoId","title":"$title","channelTitle":"$channel","watchUrl":"$watch","cache":"MISS"}""",
            YouTubeResolverMeta(),
        ).shouldBeInstanceOf<YouTubeResolveResult.Resolved>().cache shouldBe "MISS"
    }

    "query UTF-8 / Vietnamese encoding does not use +" {
        var seen: URI? = null
        val http = HttpYouTubeResolverClient(
            baseUrlProvider = { "https://resolver.test" },
            transport = { uri ->
                seen = uri
                ResolverHttpResponse(
                    200,
                    """{"status":"RESOLVED","videoId":"$videoId","title":"$title","channelTitle":"$channel","watchUrl":"$watch","cache":"MISS"}""",
                )
            },
        )
        runBlocking { http.resolve(song, "vi", "VN") }.shouldBeInstanceOf<YouTubeResolveResult.Resolved>()
        val query = seen!!.rawQuery
        query shouldContain "q=%C4%90%E1%BB%ABng%20Xa%20Em%20%C4%90%C3%AAm%20Nay"
        query shouldContain "lang=vi"
        query shouldContain "region=VN"
        query shouldNotContain "key="
        seen!!.host shouldBe "resolver.test"
        seen!!.path shouldBe "/v1/youtube/resolve"
        seen!!.scheme shouldBe "https"
    }

    "HTTP transport failures map to structured results without raw exceptions" {
        fun failing(error: Exception) = HttpYouTubeResolverClient(
            baseUrlProvider = { "https://resolver.test" },
            transport = { throw error },
        )
        runBlocking { failing(UnknownHostException("dns")).resolve(song) }
            .shouldBeInstanceOf<YouTubeResolveResult.NetworkUnavailable>()
        runBlocking { failing(ConnectException("refused")).resolve(song) }
            .shouldBeInstanceOf<YouTubeResolveResult.NetworkUnavailable>()
        runBlocking { failing(SocketTimeoutException("read")).resolve(song) }
            .shouldBeInstanceOf<YouTubeResolveResult.Timeout>()
    }

    "empty base URL does not attempt HTTP" {
        var called = false
        val http = HttpYouTubeResolverClient(
            baseUrlProvider = { "  " },
            transport = { called = true; error("no") },
        )
        val result = runBlocking { http.resolve(song) }
        result.shouldBeInstanceOf<YouTubeResolveResult.ResolverUnavailable>()
        result.meta().requestAttempted shouldBe false
        result.meta().baseUrlConfigured shouldBe false
        called shouldBe false
    }

    "resolver jack ignores Accessibility fallback and HTML search" {
        val search = FakeYouTubeQuerySearchClient(
            result = YouTubeQuerySearchResult.Hits(
                listOf(YouTubeQuerySearchHit(videoId, title)),
            ),
        )
        val fake = client(YouTubeResolveResult.NoResults(YouTubeResolverMeta(requestAttempted = true, baseUrlConfigured = true)))
        val selector = FakeYouTubeInAppSelector()
        val (playAuto, adapter, _) = driver(
            client = fake,
            selector = selector,
            options = YouTubePlayAutoOptions(accessibilityFallbackEnabled = true),
            content = SearchingYouTubeContentResolver(search),
        )
        val result = playAuto.execute(PlayAutoRequest("YouTube", song), YouTubeLaunchMode.DEVICE_TEST)
        result.accessibilityFallbackUsed shouldBe false
        result.path shouldBe "RESOLVER_DIRECT_TARGET"
        selector.selectCount shouldBe 0
        search.searchCount shouldBe 0
        adapter.dispatchCount shouldBe 0
    }

    "diagnostics include required jack fields" {
        val fake = client(resolved(cache = "HIT"))
        val (playAuto, _, _) = driver(fake)
        val text = playAuto.execute(PlayAutoRequest("YouTube", song), YouTubeLaunchMode.DEVICE_TEST).formatHarness()
        text shouldContain "Resolver base URL configured: YES"
        text shouldContain "Resolver request attempted: YES"
        text shouldContain "Resolver status: RESOLVED"
        text shouldContain "HTTP status: 200"
        text shouldContain "Resolved videoId: $videoId"
        text shouldContain "Resolved channel: $channel"
        text shouldContain "Resolved watchUrl: $watch"
        text shouldContain "Resolver cache: HIT"
        text shouldContain "Path: RESOLVER_DIRECT_TARGET"
        text shouldContain "Accessibility fallback used: NO"
        text shouldContain "Cast APIs used: NO"
        text shouldContain "Media keys sent: NO"
    }

    "no Google API key in Android jack sources" {
        val root = java.io.File("src/main/kotlin").takeIf { it.exists() }
            ?: java.io.File("youtube-playauto-android/src/main/kotlin")
        val files = root.walkTopDown().filter { it.extension == "kt" }.toList()
        files.isEmpty() shouldBe false
        files.forEach { file ->
            val text = file.readText()
            text.shouldNotContain("YOUTUBE_API_KEY")
            text.shouldNotContain("AIza")
            text.shouldNotContain("googleapis.com/youtube/v3")
            text.shouldNotContain("CastContext")
            text.shouldNotContain("KEYCODE_MEDIA_PLAY")
            text.shouldNotContain("dispatchMediaKeyEvent")
        }
    }

    "malformed RESOLVED payload is rejected" {
        YouTubeResolverJson.parse(
            200,
            """{"status":"RESOLVED","title":"x"}""",
            YouTubeResolverMeta(),
        ).shouldBeInstanceOf<YouTubeResolveResult.InvalidResponse>()
        YouTubeResolverJson.parse(
            200,
            """{"status":"RESOLVED","videoId":"short","watchUrl":"https://www.youtube.com/watch?v=short"}""",
            YouTubeResolverMeta(),
        ).shouldBeInstanceOf<YouTubeResolveResult.InvalidResponse>()
        YouTubeResolverJson.parse(
            200,
            """{"status":"RESOLVED","videoId":"$videoId","watchUrl":"https://example.com/not-youtube"}""",
            YouTubeResolverMeta(),
        ).shouldBeInstanceOf<YouTubeResolveResult.InvalidResponse>()
    }
})
