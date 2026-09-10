package org.stypox.dicio.io.session

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.stypox.dicio.youtubeplayauto.FakeYouTubeResolverClient
import org.stypox.dicio.youtubeplayauto.FakeYouTubeRuntime
import org.stypox.dicio.youtubeplayauto.PlayAutoRequest
import org.stypox.dicio.youtubeplayauto.YouTubeLaunchMode
import org.stypox.dicio.youtubeplayauto.YouTubeResolveResult
import org.stypox.dicio.youtubeplayauto.YouTubeResolverMeta

/**
 * Phase 4.9.3 — production PlayMedia(YouTube) jack onto the frozen PlayAuto driver.
 * Does not re-parse Vietnamese. Does not use the legacy search executor.
 */
class YouTubeProductionJackTest : StringSpec({
    beforeTest {
        SessionCommandDecision.resetForTests()
        CanonicalActionGate.resetForTests()
        VoiceSessionManager.resetForTests()
        VoiceTriggerManager.resetForTests()
        CommandSessionOutcome.resetForTests()
        MediaProviderExecutor.resetForTests()
        StableCompletePartialTracker.resetForTests()
    }

    val song = "Đừng Xa Em Đêm Nay"
    val watch = "https://www.youtube.com/watch?v=W20zl5N_jbg"
    val videoId = "W20zl5N_jbg"

    fun platform(): Phase3FakePlatform = Phase3FakePlatform()

    fun resolvedClient() = FakeYouTubeResolverClient(
        result = YouTubeResolveResult.Resolved(
            videoId = videoId,
            title = "$song - Hồ Hoàng Yến [Official 4K MV]",
            channelTitle = "MMG Studios",
            watchUrl = watch,
            cache = "MISS",
            meta = YouTubeResolverMeta(
                httpStatus = 200,
                requestAttempted = true,
                baseUrlConfigured = true,
                usedHttps = true,
            ),
        ),
    )

    fun executor(
        p: Phase3FakePlatform = platform(),
        youtube: YouTubePlayAutoPort,
    ) = CanonicalCommandExecutor(
        platform = p,
        appResolver = InstalledAppResolver(
            listLaunchable = { p.listLaunchableApps() },
            isLaunchable = { p.isPackageLaunchable(it) },
        ),
        youtubePlayAuto = youtube,
    )

    fun driverPort(
        client: FakeYouTubeResolverClient = resolvedClient(),
        runtime: FakeYouTubeRuntime = FakeYouTubeRuntime(),
    ): Pair<YouTubePlayAutoPort, FakeYouTubeRuntime> {
        val port = DriverBackedYouTubePlayAutoPort {
            YouTubeProductionJack.driver(runtime = runtime, resolverClient = client)
        }
        return port to runtime
    }

    "A. PlayMedia YouTube → exactly one PlayAuto request with clean query" {
        val client = resolvedClient()
        val (port, runtime) = driverPort(client)
        val p = platform()
        val trace = executor(p, port).executeTraced(
            CanonicalCommand.PlayMedia(song, "YouTube"),
        )
        client.resolveCount shouldBe 1
        client.lastQuery shouldBe song
        client.queries shouldBe listOf(song)
        runtime.dispatchCount shouldBe 1
        runtime.lastSpec!!.uri shouldBe watch
        runtime.lastSpec!!.uri!!.shouldNotContain("search_query=")
        trace.actionTaken.shouldBeTrue()
        trace.mediaQuery shouldBe song
        trace.mediaData shouldBe watch
        trace.reason shouldBe "youtube_playauto_direct_target"
        MediaProviderExecutor.legacyYoutubeSearchCount shouldBe 0
        p.activities.shouldBeEmpty()
    }

    "B. Mở bài hát ... NLU is unchanged" {
        val result = VietnameseCommandUnderstanding.understand(
            "Mở bài hát Đừng Xa Em Đêm Nay trên YouTube",
        )
        result.command shouldBe CanonicalCommand.PlayMedia(song, "YouTube")
        result.query shouldBe song
        result.provider shouldBe "YouTube"
        result.rawTranscript shouldBe "Mở bài hát Đừng Xa Em Đêm Nay trên YouTube"
    }

    "C. Mở ... trên YouTube NLU is unchanged" {
        val result = VietnameseCommandUnderstanding.understand(
            "Mở bài Đừng Xa Em Đêm Nay trên YouTube",
        )
        result.command shouldBe CanonicalCommand.PlayMedia(song, "YouTube")
        result.intent shouldBe VoiceIntent.PLAY_MEDIA
        result.executable.shouldBeTrue()
    }

    "D. Cho tôi nghe ... Phase 4.5 behavior is unchanged" {
        val result = VietnameseCommandUnderstanding.understand(
            "Cho tôi nghe Đừng Xa Em Đêm Nay trên YouTube",
        )
        result.command shouldBe CanonicalCommand.PlayMedia(song, "YouTube")
        result.query shouldBe song
        result.rawTranscript shouldBe "Cho tôi nghe Đừng Xa Em Đêm Nay trên YouTube"
    }

    "spoken sentence is not sent to the resolver" {
        val client = resolvedClient()
        val (port, _) = driverPort(client)
        val understood = VietnameseCommandUnderstanding.understand(
            "Mở bài Đừng Xa Em Đêm Nay trên YouTube",
        )
        understood.command.shouldBeInstanceOf<CanonicalCommand.PlayMedia>()
        executor(youtube = port).executeTraced(understood.command!!)
        client.lastQuery shouldBe song
        client.lastQuery!!.shouldNotContain("Mở bài")
        client.lastQuery!!.shouldNotContain("trên YouTube")
    }

    "E. YouTube route does not call the legacy search executor" {
        val (port, runtime) = driverPort()
        val p = platform()
        val trace = executor(p, port).executeTraced(
            CanonicalCommand.PlayMedia(song, "YouTube"),
        )
        MediaProviderExecutor.legacyYoutubeSearchCount shouldBe 0
        MediaProviderExecutor.build(song, "YouTube")
            .shouldBeInstanceOf<MediaProviderExecutor.Result.YouTubePlayAuto>()
        p.activities.shouldBeEmpty()
        runtime.lastSpec!!.uri!!.shouldNotContain("results?")
        trace.mediaData!!.shouldNotContain("search_query=")
        trace.reason.shouldNotContain("youtube_search")
    }

    "F. one accepted command cannot produce two YouTube launches" {
        CanonicalActionGate.bind(9001L)
        CanonicalActionGate.tryClaim(9001L).shouldBeTrue()
        val client = resolvedClient()
        val runtime = FakeYouTubeRuntime()
        val driver = YouTubeProductionJack.driver(runtime = runtime, resolverClient = client)
        val port = DriverBackedYouTubePlayAutoPort { driver }
        val p = platform()
        val trace = executor(p, port).executeTraced(CanonicalCommand.PlayMedia(song, "YouTube"))
        trace.actionTaken.shouldBeTrue()
        runtime.dispatchCount shouldBe 1
        client.resolveCount shouldBe 1
        driver.executeCount shouldBe 1
        CanonicalActionGate.tryClaim(9001L).shouldBeFalse()
        CanonicalActionGate.markCompleted(9001L)
        CanonicalActionGate.mayAct(9001L).shouldBeFalse()
        MediaProviderExecutor.legacyYoutubeSearchCount shouldBe 0
        p.activities.shouldBeEmpty()
        // Same driver instance must not dispatch a second launch.
        val second = driver.execute(
            PlayAutoRequest("YouTube", song),
            YouTubeLaunchMode.DEVICE_TEST,
        )
        runtime.dispatchCount shouldBe 1
        second.resolvedVideoId shouldBe videoId
        second.targetUri shouldBe watch
        (second.failure == "duplicate_request" || second.failure == null).shouldBeTrue()
    }

    "G. resolver failure produces zero YouTube launches" {
        val failures = listOf(
            YouTubeResolveResult.NoResults() to "NO_RESULTS",
            YouTubeResolveResult.NetworkUnavailable() to "NETWORK_UNAVAILABLE",
            YouTubeResolveResult.ResolverUnavailable() to "RESOLVER_UNAVAILABLE",
            YouTubeResolveResult.QuotaExceeded() to "QUOTA_EXCEEDED",
            YouTubeResolveResult.InvalidResponse() to "INVALID_RESPONSE",
            YouTubeResolveResult.Timeout() to "TIMEOUT",
        )
        failures.forEach { (outcome, status) ->
            MediaProviderExecutor.resetForTests()
            val client = FakeYouTubeResolverClient(result = outcome)
            val runtime = FakeYouTubeRuntime()
            val (port, _) = driverPort(client, runtime)
            val p = platform()
            val trace = executor(p, port).executeTraced(
                CanonicalCommand.PlayMedia(song, "YouTube"),
            )
            trace.actionTaken.shouldBeFalse()
            trace.mediaData.shouldBeNull()
            runtime.dispatchCount shouldBe 0
            MediaProviderExecutor.legacyYoutubeSearchCount shouldBe 0
            p.activities.shouldBeEmpty()
            trace.reason shouldBe status
        }
    }

    "H. unknown/non-YouTube provider is not routed to YouTube" {
        val client = resolvedClient()
        val (port, runtime) = driverPort(client)
        val p = platform()
        val exec = executor(p, port)
        listOf(
            "SmartTube" to "unknown_provider",
            "MusicLoop" to "unknown_provider",
            "SpotifyX" to "unknown_provider",
            null to "missing_provider",
        ).forEach { (provider, reason) ->
            val trace = exec.executeTraced(CanonicalCommand.PlayMedia(song, provider))
            trace.actionTaken.shouldBeFalse()
            trace.reason shouldContain reason
        }
        client.resolveCount shouldBe 0
        runtime.dispatchCount shouldBe 0
        MediaProviderExecutor.legacyYoutubeSearchCount shouldBe 0
        p.activities.shouldBeEmpty()
    }

    "unconfigured jack does not fall back to legacy YouTube search" {
        val p = platform()
        val exec = CanonicalCommandExecutor(
            platform = p,
            appResolver = InstalledAppResolver(
                listLaunchable = { p.listLaunchableApps() },
                isLaunchable = { p.isPackageLaunchable(it) },
            ),
            youtubePlayAuto = null,
        )
        val trace = exec.executeTraced(CanonicalCommand.PlayMedia(song, "YouTube"))
        trace.actionTaken.shouldBeFalse()
        trace.reason shouldBe "youtube_playauto_unconfigured"
        MediaProviderExecutor.legacyYoutubeSearchCount shouldBe 0
        p.activities.shouldBeEmpty()
    }

    "cast / a11y / media-key stay off on the production jack" {
        val client = resolvedClient()
        val runtime = FakeYouTubeRuntime()
        val driver = YouTubeProductionJack.driver(runtime, client)
        val result = driver.execute(
            PlayAutoRequest("YouTube", song),
            YouTubeLaunchMode.DEVICE_TEST,
        )
        result.accessibilityFallbackUsed.shouldBeFalse()
        result.castApisUsed.shouldBeFalse()
        result.mediaKeysSent.shouldBeFalse()
        result.searchOpened.shouldBeFalse()
        result.path shouldBe "RESOLVER_DIRECT_TARGET"
    }
})
