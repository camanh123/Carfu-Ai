package org.stypox.dicio.youtubeplayauto

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.stypox.dicio.playauto.core.PlaybackStrategy

class YouTubePlayAutoDriverTest : StringSpec({
    val song = "Đừng Xa Em Đêm Nay"

    fun driver(
        runtime: FakeYouTubeRuntime = FakeYouTubeRuntime(),
        selector: FakeYouTubeInAppSelector = FakeYouTubeInAppSelector(),
        mode: YouTubeLaunchMode = YouTubeLaunchMode.DEVICE_TEST,
    ): Triple<YouTubePlayAutoDriver, YouTubeMediaAdapter, FakeYouTubeInAppSelector> {
        val adapter = YouTubeMediaAdapter(runtime, launchMode = mode).also { it.detect() }
        return Triple(YouTubePlayAutoDriver(adapter, selector), adapter, selector)
    }

    "structured YouTube request searches then selects and requests play" {
        val (playAuto, adapter, selector) = driver()
        val result = playAuto.execute(PlayAutoRequest("YouTube", song), YouTubeLaunchMode.DEVICE_TEST)
        result.searchOpened shouldBe true
        result.resultSelected shouldBe true
        result.playbackRequested shouldBe true
        result.matchedTitle shouldBe song
        result.failure shouldBe null
        result.searchDispatchCount shouldBe 1
        adapter.dispatchCount shouldBe 1
        adapter.lastStrategy shouldBe PlaybackStrategy.SEARCH
        selector.selectCount shouldBe 1
        selector.lastQuery shouldBe song
    }

    "does not re-parse Vietnamese commands; query is used as the media title" {
        val (playAuto, _, selector) = driver()
        playAuto.execute(
            PlayAutoRequest("YouTube", song),
            YouTubeLaunchMode.DEVICE_TEST,
        )
        selector.lastQuery shouldBe song
        selector.lastQuery shouldNotBe "Mở bài Đừng Xa Em Đêm Nay trên YouTube"
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

    "selector failure leaves YouTube open and does not search twice" {
        val selector = FakeYouTubeInAppSelector(selectSucceeds = false)
        val (playAuto, adapter, _) = driver(selector = selector)
        val result = playAuto.execute(PlayAutoRequest("YouTube", song), YouTubeLaunchMode.DEVICE_TEST)
        result.searchOpened shouldBe true
        result.resultSelected shouldBe false
        result.playbackRequested shouldBe false
        result.youtubeLeftOpen shouldBe true
        result.failure shouldBe "no_matching_result"
        result.searchDispatchCount shouldBe 1
        adapter.dispatchCount shouldBe 1
        playAuto.executeCount shouldBe 1
    }

    "unavailable selector still opens search and does not duplicate execution" {
        val selector = FakeYouTubeInAppSelector(available = false)
        val (playAuto, adapter, _) = driver(selector = selector)
        val result = playAuto.execute(PlayAutoRequest("YouTube", song), YouTubeLaunchMode.DEVICE_TEST)
        result.searchOpened shouldBe true
        result.resultSelected shouldBe false
        result.youtubeLeftOpen shouldBe true
        result.failure shouldBe "in_app_selector_unavailable"
        adapter.dispatchCount shouldBe 1
        adapter.dispatchCount shouldBeLessThanOrEqual 1
    }

    "dry-run does not request in-app click" {
        val selector = FakeYouTubeInAppSelector()
        val (playAuto, _, _) = driver(selector = selector, mode = YouTubeLaunchMode.DRY_RUN)
        val result = playAuto.execute(PlayAutoRequest("YouTube", song), YouTubeLaunchMode.DRY_RUN)
        result.searchOpened shouldBe true
        result.resultSelected shouldBe false
        result.playbackRequested shouldBe false
        selector.selectCount shouldBe 0
    }

    "YouTube unavailable fails without select" {
        val (playAuto, adapter, selector) = driver(runtime = FakeYouTubeRuntime(installation = null))
        val result = playAuto.execute(PlayAutoRequest("YouTube", song), YouTubeLaunchMode.DEVICE_TEST)
        result.searchOpened shouldBe false
        result.resultSelected shouldBe false
        selector.selectCount shouldBe 0
        adapter.dispatchCount shouldBe 0
        result.failure.shouldNotBeNull()
    }

    "title matcher selects the query match and ignores chrome/home chrome" {
        val best = YouTubeSearchResultPicker.bestSelectable(
            titles = listOf(
                "Home",
                "Search YouTube",
                "Đừng Xa Em Đêm Nay - Official Audio",
                "Unrelated mix 2020",
            ),
            query = song,
        )
        best.shouldNotBeNull()
        best.text shouldBe "Đừng Xa Em Đêm Nay - Official Audio"
        best.score shouldBe 100
        YouTubeTitleMatcher.score("Totally different song", song) shouldBe 0
        YouTubeSearchResultPicker.shouldSkipText("Shorts") shouldBe true
    }

    "one driver execute performs at most one search dispatch" {
        val (playAuto, adapter, selector) = driver()
        playAuto.execute(PlayAutoRequest("yt", song), YouTubeLaunchMode.DEVICE_TEST)
        adapter.dispatchCount shouldBe 1
        selector.selectCount shouldBe 1
        playAuto.executeCount shouldBe 1
    }
})
