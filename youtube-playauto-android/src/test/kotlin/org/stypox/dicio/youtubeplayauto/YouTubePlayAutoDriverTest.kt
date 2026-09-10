package org.stypox.dicio.youtubeplayauto

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.stypox.dicio.playauto.core.PlaybackStrategy

class YouTubePlayAutoDriverTest : StringSpec({
    val song = "Đừng Xa Em Đêm Nay"
    val official = "$song - Hồ Hoàng Yến [Official 4K MV]"
    val videoId = "AbCdeFghi_K"
    val watch = YouTubeVideoIdParser.canonicalWatchUri(videoId)

    val officialHit = YouTubeQuerySearchHit(videoId = videoId, title = official)

    fun searchClient(
        vararg hits: YouTubeQuerySearchHit = arrayOf(officialHit),
    ) = FakeYouTubeQuerySearchClient(
        result = YouTubeQuerySearchResult.Hits(hits.toList()),
    )

    fun resolver(client: FakeYouTubeQuerySearchClient = searchClient()) =
        defaultHarnessContentResolver(client)

    fun driver(
        runtime: FakeYouTubeRuntime = FakeYouTubeRuntime(),
        selector: FakeYouTubeInAppSelector = FakeYouTubeInAppSelector(),
        mode: YouTubeLaunchMode = YouTubeLaunchMode.DEVICE_TEST,
        content: YouTubeContentResolver = resolver(),
        options: YouTubePlayAutoOptions = YouTubePlayAutoOptions(),
    ): Triple<YouTubePlayAutoDriver, YouTubeMediaAdapter, FakeYouTubeInAppSelector> {
        val adapter = YouTubeMediaAdapter(runtime, launchMode = mode).also { it.detect() }
        return Triple(
            YouTubePlayAutoDriver(adapter, selector, content, options),
            adapter,
            selector,
        )
    }

    "valid resolved video id launches watch URI once without accessibility" {
        val (playAuto, adapter, selector) = driver()
        val result = playAuto.execute(PlayAutoRequest("YouTube", song), YouTubeLaunchMode.DEVICE_TEST)
        result.resolverSuccess shouldBe true
        result.resolvedVideoId shouldBe videoId
        result.targetUri shouldBe watch
        result.path shouldBe "DIRECT_TARGET"
        result.accessibilityFallbackUsed shouldBe false
        result.launchAttempted shouldBe true
        result.searchOpened shouldBe false
        result.selectAttemptCount shouldBe 0
        selector.selectCount shouldBe 0
        adapter.dispatchCount shouldBe 1
        adapter.lastStrategy shouldBe PlaybackStrategy.DEEP_LINK
        adapter.lastSpec?.uri shouldBe watch
        adapter.lastSpec?.packageName shouldBe YouTubeCandidatePackages.OFFICIAL
        result.castApisUsed shouldBe false
        result.mediaKeysSent shouldBe false
        result.playbackRequested shouldBe true
    }

    "invalid video id does not launch" {
        val bad = ResolvedYouTubeTarget(
            videoId = "not-valid",
            canonicalUri = "https://www.youtube.com/watch?v=not-valid",
            source = "test",
        )
        val (playAuto, adapter, selector) = driver()
        val result = playAuto.openExact(bad, song, YouTubeLaunchMode.DEVICE_TEST)
        result.failure shouldBe "invalid_video_id"
        result.launchAttempted shouldBe false
        adapter.dispatchCount shouldBe 0
        selector.selectCount shouldBe 0
        YouTubeVideoIdParser.parse("not-valid").shouldBeNull()
        YouTubeVideoIdParser.parse("short").shouldBeNull()
        YouTubeVideoIdParser.parse(song).shouldBeNull()
    }

    "target watch URI construction is canonical" {
        YouTubeVideoIdParser.canonicalWatchUri(videoId) shouldBe
            "https://www.youtube.com/watch?v=$videoId"
        YouTubeVideoIdParser.parse("https://youtu.be/$videoId") shouldBe videoId
        YouTubeVideoIdParser.parse(watch) shouldBe videoId
        YouTubeDeepLinkStrategy.build(
            YouTubeStrategyInput(
                packageName = YouTubeCandidatePackages.OFFICIAL,
                query = song,
                resolved = ResolvedYouTubeTarget(videoId, watch, source = "test"),
            ),
        ).shouldBeInstanceOf<YouTubeStrategyBuild.Ok>()
            .spec.uri shouldBe watch
    }

    "exact package launch pins installed YouTube" {
        val (playAuto, adapter, _) = driver()
        playAuto.execute(PlayAutoRequest("YouTube", song), YouTubeLaunchMode.DEVICE_TEST)
        adapter.lastSpec?.packageName shouldBe YouTubeCandidatePackages.OFFICIAL
        adapter.lastSpec?.action shouldBe YouTubeLaunchSpec.ACTION_VIEW
    }

    "no accessibility click in primary path" {
        val (playAuto, _, selector) = driver()
        playAuto.execute(PlayAutoRequest("YouTube", song), YouTubeLaunchMode.DEVICE_TEST)
        selector.selectCount shouldBe 0
        selector.lastQuery.shouldBeNull()
    }

    "no Cast API usage and no media keys on result" {
        val (playAuto, _, _) = driver()
        val result = playAuto.execute(PlayAutoRequest("YouTube", song), YouTubeLaunchMode.DEVICE_TEST)
        result.castApisUsed shouldBe false
        result.mediaKeysSent shouldBe false
        result.formatHarness() shouldContain "Cast APIs used: NO"
        result.formatHarness() shouldContain "Media keys sent: NO"
    }

    "exactly one launch" {
        val runtime = FakeYouTubeRuntime()
        val (playAuto, adapter, _) = driver(runtime = runtime)
        playAuto.execute(PlayAutoRequest("YouTube", song), YouTubeLaunchMode.DEVICE_TEST)
        adapter.dispatchCount shouldBe 1
        runtime.dispatchCount shouldBe 1
        adapter.dispatchCount shouldBeLessThanOrEqual 1
        playAuto.executeCount shouldBe 1
    }

    "resolver failure does not launch when fallback is disabled" {
        val (playAuto, adapter, selector) = driver(content = NoOpYouTubeContentResolver)
        val result = playAuto.execute(PlayAutoRequest("YouTube", song), YouTubeLaunchMode.DEVICE_TEST)
        result.resolverSuccess shouldBe false
        result.failure shouldBe "no_android_title_to_video_id_resolver"
        result.launchAttempted shouldBe false
        adapter.dispatchCount shouldBe 0
        selector.selectCount shouldBe 0
        result.accessibilityFallbackUsed shouldBe false
    }

    "fallback disabled by default" {
        YouTubePlayAutoOptions().accessibilityFallbackEnabled shouldBe false
        val (playAuto, _, selector) = driver(content = NoOpYouTubeContentResolver)
        playAuto.execute(PlayAutoRequest("YouTube", song), YouTubeLaunchMode.DEVICE_TEST)
        selector.selectCount shouldBe 0
    }

    "does not re-parse Vietnamese commands; query is the media title" {
        val client = searchClient()
        val (playAuto, _, _) = driver(content = resolver(client))
        playAuto.execute(PlayAutoRequest("YouTube", song), YouTubeLaunchMode.DEVICE_TEST)
        client.lastQuery shouldBe song
        client.lastQuery shouldNotBe "Mở bài Đừng Xa Em Đêm Nay trên YouTube"
    }

    "blank query does not open YouTube" {
        val (playAuto, adapter, selector) = driver()
        val result = playAuto.execute(PlayAutoRequest("YouTube", "   "), YouTubeLaunchMode.DEVICE_TEST)
        result.searchOpened shouldBe false
        result.failure shouldBe "blank_query"
        adapter.dispatchCount shouldBe 0
        selector.selectCount shouldBe 0
    }

    "non-YouTube targetApp is rejected without launching" {
        val (playAuto, adapter, selector) = driver()
        val result = playAuto.execute(PlayAutoRequest("Chrome", song), YouTubeLaunchMode.DEVICE_TEST)
        result.searchOpened shouldBe false
        result.failure shouldBe "unsupported_target_app"
        adapter.dispatchCount shouldBe 0
        selector.selectCount shouldBe 0
        playAuto.canHandle(PlayAutoRequest("Chrome", song)) shouldBe false
        playAuto.canHandle(PlayAutoRequest("YouTube", song)) shouldBe true
    }

    "dry-run does not request in-app click" {
        val selector = FakeYouTubeInAppSelector()
        val (playAuto, adapter, _) = driver(
            selector = selector,
            mode = YouTubeLaunchMode.DRY_RUN,
        )
        val result = playAuto.execute(PlayAutoRequest("YouTube", song), YouTubeLaunchMode.DRY_RUN)
        result.resolverSuccess shouldBe true
        result.playbackRequested shouldBe false
        result.accessibilityFallbackUsed shouldBe false
        selector.selectCount shouldBe 0
        adapter.lastStrategy shouldBe PlaybackStrategy.DEEP_LINK
    }

    "YouTube unavailable fails without select" {
        val (playAuto, adapter, selector) = driver(runtime = FakeYouTubeRuntime(installation = null))
        val result = playAuto.execute(PlayAutoRequest("YouTube", song), YouTubeLaunchMode.DEVICE_TEST)
        result.launchAttempted shouldBe false
        result.resultSelected shouldBe false
        selector.selectCount shouldBe 0
        adapter.dispatchCount shouldBe 0
        result.failure.shouldNotBeNull()
    }

    "accessibility fallback is used only when enabled and resolve fails" {
        val selector = FakeYouTubeInAppSelector()
        val (playAuto, adapter, _) = driver(
            selector = selector,
            content = NoOpYouTubeContentResolver,
            options = YouTubePlayAutoOptions(accessibilityFallbackEnabled = true),
        )
        val result = playAuto.execute(PlayAutoRequest("YouTube", song), YouTubeLaunchMode.DEVICE_TEST)
        result.accessibilityFallbackUsed shouldBe true
        result.path shouldBe "ACCESSIBILITY_FALLBACK"
        result.searchOpened shouldBe true
        selector.selectCount shouldBe 1
        adapter.lastStrategy shouldBe PlaybackStrategy.SEARCH
    }

    "query that is already a watch URL uses local parse and does not search" {
        val client = searchClient()
        val (playAuto, adapter, selector) = driver(content = resolver(client))
        val result = playAuto.execute(PlayAutoRequest("YouTube", watch), YouTubeLaunchMode.DEVICE_TEST)
        result.resolutionMethod shouldBe "parsed_id_or_watch_url"
        result.resolvedVideoId shouldBe videoId
        client.searchCount shouldBe 0
        selector.selectCount shouldBe 0
        adapter.dispatchCount shouldBe 1
    }

    "title matcher still ranks official over remix for search hits" {
        val remixId = "RemixVideo1"
        val client = searchClient(
            YouTubeQuerySearchHit(remixId, "$song remix karaoke"),
            officialHit,
        )
        SearchingYouTubeContentResolver(client).resolveQuery(song)
            .shouldBeInstanceOf<YouTubeContentResolution.Resolved>()
            .target.videoId shouldBe videoId
    }
})
