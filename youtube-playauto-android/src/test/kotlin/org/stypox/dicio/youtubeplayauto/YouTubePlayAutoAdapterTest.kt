package org.stypox.dicio.youtubeplayauto

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.stypox.dicio.playauto.adapter.ExecuteOutcome
import org.stypox.dicio.playauto.adapter.ResolveOutcome
import org.stypox.dicio.playauto.core.MediaRequest
import org.stypox.dicio.playauto.core.MediaType
import org.stypox.dicio.playauto.core.PlayAutoEngine
import org.stypox.dicio.playauto.core.PlaybackCapability
import org.stypox.dicio.playauto.core.PlaybackFailureReason
import org.stypox.dicio.playauto.core.PlaybackResult
import org.stypox.dicio.playauto.core.PlaybackStrategy
import org.stypox.dicio.playauto.provider.MediaProvider
import org.stypox.dicio.playauto.provider.ProviderRegistry

class YouTubePlayAutoAdapterTest : StringSpec({
    val song = "Đừng Xa Em Đêm Nay"

    fun request(
        query: String = song,
        type: MediaType = MediaType.AUDIO,
    ) = MediaRequest(
        query = query,
        mediaType = type,
        preferredProvider = MediaProvider.YOUTUBE,
    )

    fun installedRuntime() = FakeYouTubeRuntime()

    fun unavailableRuntime() = FakeYouTubeRuntime(installation = null)

    fun adapter(runtime: FakeYouTubeRuntime = installedRuntime()) =
        YouTubeMediaAdapter(runtime).also { it.detect() }

    fun engineFor(adapter: YouTubeMediaAdapter): PlayAutoEngine {
        val registry = ProviderRegistry()
        registry.register(adapter)
        return PlayAutoEngine(registry)
    }

    val fakeVideo = ResolvedYouTubeTarget(
        videoId = "abcdefghijk",
        canonicalUri = YouTubeVideoIdParser.canonicalWatchUri("abcdefghijk"),
        title = "fixture",
        source = "injected",
    )

    "YouTube unavailable yields unavailable result" {
        val youtube = adapter(unavailableRuntime())
        youtube.available shouldBe false
        youtube.lastSnapshot.installed shouldBe false
        youtube.lastSnapshot.engineCapabilities.isEmpty() shouldBe true
        val result = engineFor(youtube).execute(request()).shouldBeInstanceOf<PlaybackResult.Failure>()
        result.reason shouldBe PlaybackFailureReason.PROVIDER_UNAVAILABLE
        youtube.executionCount shouldBe 0
        youtube.dispatchCount shouldBe 0
    }

    "YouTube installed exposes OPEN_APP when resolvable" {
        val youtube = adapter()
        youtube.lastSnapshot.installed shouldBe true
        youtube.lastSnapshot.packageName shouldBe YouTubeCandidatePackages.OFFICIAL
        youtube.lastSnapshot.launchActivity shouldNotBe null
        youtube.lastSnapshot.openAppResolvable shouldBe true
        youtube.capabilities shouldContain PlaybackCapability.OPEN_APP
        val built = YouTubeOpenAppStrategy.build(
            YouTubeStrategyInput(youtube.lastSnapshot.packageName, query = song),
        ).shouldBeInstanceOf<YouTubeStrategyBuild.Ok>()
        built.spec.packageName shouldBe YouTubeCandidatePackages.OFFICIAL
        built.spec.action shouldBe YouTubeLaunchSpec.ACTION_MAIN
    }

    "non-empty search query builds a valid SEARCH request" {
        val built = YouTubeSearchStrategy.build(
            YouTubeStrategyInput(YouTubeCandidatePackages.OFFICIAL, query = song),
        ).shouldBeInstanceOf<YouTubeStrategyBuild.Ok>()
        built.spec.strategy shouldBe PlaybackStrategy.SEARCH
        built.spec.uri shouldContain "search_query="
        built.spec.uri.orEmpty() shouldContain "Em"
        built.spec.extraQuery shouldBe song
        val youtube = adapter()
        val outcome = youtube.executeExplicit(request(), PlaybackStrategy.SEARCH)
            .shouldBeInstanceOf<ExecuteOutcome.Accepted>()
        outcome.strategy shouldBe PlaybackStrategy.SEARCH
        youtube.lastSpec?.uri.orEmpty() shouldContain "results?search_query="
        youtube.dispatchCount shouldBe 1
    }

    "blank search query fails and does not dispatch" {
        val youtube = adapter()
        val outcome = youtube.executeExplicit(request(query = "   "), PlaybackStrategy.SEARCH)
            .shouldBeInstanceOf<ExecuteOutcome.Failed>()
        outcome.reason shouldBe PlaybackFailureReason.INVALID_REQUEST
        youtube.dispatchCount shouldBe 0
        engineFor(adapter()).execute(request(query = ""))
            .shouldBeInstanceOf<PlaybackResult.Failure>()
            .reason shouldBe PlaybackFailureReason.INVALID_REQUEST
    }

    "known fake resolved video target builds a typed DEEP_LINK request" {
        val built = YouTubeDeepLinkStrategy.build(
            YouTubeStrategyInput(
                packageName = YouTubeCandidatePackages.OFFICIAL,
                query = song,
                resolved = fakeVideo,
            ),
        ).shouldBeInstanceOf<YouTubeStrategyBuild.Ok>()
        built.spec.strategy shouldBe PlaybackStrategy.DEEP_LINK
        built.spec.uri shouldBe "https://www.youtube.com/watch?v=abcdefghijk"
        built.spec.packageName shouldBe YouTubeCandidatePackages.OFFICIAL
        val youtube = adapter()
        youtube.executeExplicit(request(), PlaybackStrategy.DEEP_LINK, fakeVideo)
            .shouldBeInstanceOf<ExecuteOutcome.Accepted>()
        youtube.lastSpec?.uri shouldBe fakeVideo.canonicalUri
    }

    "DIRECT_PLAY requires resolved target and a supported implementation" {
        val withoutTarget = YouTubeDirectPlayStrategy.build(
            YouTubeStrategyInput(YouTubeCandidatePackages.OFFICIAL, query = song, resolved = null),
        ).shouldBeInstanceOf<YouTubeStrategyBuild.Failed>()
        withoutTarget.reason shouldBe PlaybackFailureReason.NO_PLAYBACK_STRATEGY

        val withTarget = YouTubeDirectPlayStrategy.build(
            YouTubeStrategyInput(YouTubeCandidatePackages.OFFICIAL, query = song, resolved = fakeVideo),
        ).shouldBeInstanceOf<YouTubeStrategyBuild.Failed>()
        withTarget.detail shouldBe YouTubeDirectPlayStrategy.UNSUPPORTED_DETAIL
    }

    "no resolved target makes DIRECT_PLAY unavailable" {
        adapter().capabilities shouldNotContain PlaybackCapability.DIRECT_PLAY
        val youtube = adapter()
        val outcome = youtube.executeExplicit(request(), PlaybackStrategy.DIRECT_PLAY)
            .shouldBeInstanceOf<ExecuteOutcome.Failed>()
        outcome.reason shouldBe PlaybackFailureReason.NO_PLAYBACK_STRATEGY
        youtube.dispatchCount shouldBe 0
    }

    "unresolved activity is a typed failure and does not crash" {
        val runtime = FakeYouTubeRuntime(
            resolvable = setOf(PlaybackStrategy.OPEN_APP),
        )
        val youtube = adapter(runtime)
        val outcome = youtube.executeExplicit(request(), PlaybackStrategy.SEARCH)
            .shouldBeInstanceOf<ExecuteOutcome.Failed>()
        outcome.reason shouldBe PlaybackFailureReason.EXECUTION_FAILED
        youtube.dispatchCount shouldBe 0
        runtime.dispatchCount shouldBe 0
    }

    "one PlayAutoEngine.execute performs at most one adapter execution" {
        val youtube = adapter()
        val result = engineFor(youtube).execute(request()).shouldBeInstanceOf<PlaybackResult.Success>()
        result.provider shouldBe MediaProvider.YOUTUBE
        result.strategy shouldBe PlaybackStrategy.SEARCH
        youtube.executionCount shouldBe 1
        youtube.dispatchCount shouldBe 1
        youtube.executionCount shouldBeLessThanOrEqual 1
        youtube.dispatchCount shouldBeLessThanOrEqual 1
    }

    "PlayAutoEngine does not select DIRECT_PLAY for the YouTube adapter" {
        val youtube = adapter()
        youtube.capabilities shouldNotContain PlaybackCapability.DIRECT_PLAY
        val result = engineFor(youtube).execute(request()).shouldBeInstanceOf<PlaybackResult.Success>()
        result.strategy shouldBe PlaybackStrategy.SEARCH
    }

    "NoOp resolver does not invent a video id for a song title" {
        val resolution = NoOpYouTubeContentResolver.resolveQuery(song)
        resolution.shouldBeInstanceOf<YouTubeContentResolution.Unresolved>()
        val youtube = adapter()
        val resolved = youtube.resolve(request()).shouldBeInstanceOf<ResolveOutcome.Ok>()
        YouTubeTargetDescriptor.parseVideo(resolved.target.descriptor) shouldBe null
        resolved.target.descriptor.startsWith("youtube:query:") shouldBe true
    }

    "injected resolver can supply a video target without scraping" {
        val resolver = InjectedYouTubeContentResolver(mapOf(song to fakeVideo))
        resolver.resolveQuery(song)
            .shouldBeInstanceOf<YouTubeContentResolution.Resolved>()
            .target.videoId shouldBe "abcdefghijk"
        resolver.resolveQuery("other").shouldBeInstanceOf<YouTubeContentResolution.Unresolved>()
    }

    "YouTubeVideoIdParser accepts ids and watch URLs but not titles" {
        YouTubeVideoIdParser.parse("abcdefghijk") shouldBe "abcdefghijk"
        YouTubeVideoIdParser.parse("https://www.youtube.com/watch?v=abcdefghijk") shouldBe "abcdefghijk"
        YouTubeVideoIdParser.parse(song) shouldBe null
        YouTubeVideoIdParser.parse("") shouldBe null
    }

    "dry-run dispatch does not claim PLAYBACK_CONFIRMED" {
        val runtime = installedRuntime()
        val youtube = YouTubeMediaAdapter(runtime, launchMode = YouTubeLaunchMode.DRY_RUN)
        youtube.detect()
        youtube.executeExplicit(request(), PlaybackStrategy.SEARCH)
        youtube.lastDispatch.shouldBeInstanceOf<YouTubeDispatchOutcome.DryRun>()
            .provenance shouldBe YouTubeProvenance.DRY_RUN_SELECTED
        runtime.lastMode shouldBe YouTubeLaunchMode.DRY_RUN
    }

    "device-test dispatch provenance is INTENT_DISPATCHED not playback" {
        val runtime = installedRuntime()
        val youtube = YouTubeMediaAdapter(runtime, launchMode = YouTubeLaunchMode.DEVICE_TEST)
        youtube.detect()
        youtube.executeExplicit(request(), PlaybackStrategy.SEARCH)
        val dispatched = youtube.lastDispatch.shouldBeInstanceOf<YouTubeDispatchOutcome.Dispatched>()
        dispatched.provenance shouldBe YouTubeProvenance.INTENT_DISPATCHED
        dispatched.provenance shouldNotBe YouTubeProvenance.PLAYBACK_CONFIRMED
        dispatched.provenance shouldNotBe YouTubeProvenance.TARGET_OPENED
    }
})
