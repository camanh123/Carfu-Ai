package org.stypox.dicio.playauto.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.stypox.dicio.playauto.adapter.FakeMediaAdapter
import org.stypox.dicio.playauto.provider.MediaProvider
import org.stypox.dicio.playauto.provider.ProviderRegistry

class PlayAutoEngineTest : StringSpec({
    val song = "Đừng Xa Em Đêm Nay"

    fun registry(vararg adapters: FakeMediaAdapter, default: MediaProvider? = null): ProviderRegistry {
        val registry = ProviderRegistry()
        adapters.forEach { registry.register(it) }
        registry.defaultProvider = default
        return registry
    }

    fun engine(vararg adapters: FakeMediaAdapter, default: MediaProvider? = null) =
        PlayAutoEngine(registry(*adapters, default = default))

    fun request(
        query: String = song,
        type: MediaType = MediaType.AUDIO,
        preferred: MediaProvider? = null,
    ) = MediaRequest(query = query, mediaType = type, preferredProvider = preferred)

    fun PlaybackResult.asSuccess(): PlaybackResult.Success =
        this.shouldBeInstanceOf<PlaybackResult.Success>()

    fun PlaybackResult.asFailure(): PlaybackResult.Failure =
        this.shouldBeInstanceOf<PlaybackResult.Failure>()

    "A. blank query is INVALID_REQUEST" {
        val youtube = FakeMediaAdapter.youtube()
        engine(youtube).execute(request(query = "")).asFailure().reason shouldBe
            PlaybackFailureReason.INVALID_REQUEST
        engine(youtube).execute(request(query = "   ")).asFailure().reason shouldBe
            PlaybackFailureReason.INVALID_REQUEST
        youtube.executionCount shouldBe 0
    }

    "A. valid AUDIO request is accepted" {
        val youtube = FakeMediaAdapter.youtube()
        val result = engine(youtube).execute(request(type = MediaType.AUDIO)).asSuccess()
        result.provider shouldBe MediaProvider.YOUTUBE
        result.target.query shouldBe song
        result.target.mediaType shouldBe MediaType.AUDIO
    }

    "A. valid VIDEO request is accepted" {
        val youtube = FakeMediaAdapter.youtube()
        val result = engine(youtube).execute(request(type = MediaType.VIDEO)).asSuccess()
        result.target.mediaType shouldBe MediaType.VIDEO
        youtube.executionCount shouldBe 1
    }

    "B. explicit preferred YouTube is selected when available" {
        val youtube = FakeMediaAdapter.youtube()
        val smart = FakeMediaAdapter.smartTube()
        val result = engine(youtube, smart).execute(
            request(preferred = MediaProvider.YOUTUBE),
        ).asSuccess()
        result.provider shouldBe MediaProvider.YOUTUBE
        youtube.executionCount shouldBe 1
        smart.executionCount shouldBe 0
    }

    "C. null preferred uses configured default SmartTube" {
        val youtube = FakeMediaAdapter.youtube()
        val smart = FakeMediaAdapter.smartTube()
        val result = engine(youtube, smart, default = MediaProvider.SMARTTUBE)
            .execute(request(preferred = null))
            .asSuccess()
        result.provider shouldBe MediaProvider.SMARTTUBE
        smart.executionCount shouldBe 1
        youtube.executionCount shouldBe 0
    }

    "D. unavailable preferred YouTube falls back to next capable adapter" {
        val youtube = FakeMediaAdapter.youtube(available = false)
        val smart = FakeMediaAdapter.smartTube()
        val result = engine(youtube, smart).execute(
            request(preferred = MediaProvider.YOUTUBE),
        ).asSuccess()
        result.provider shouldBe MediaProvider.SMARTTUBE
        youtube.executionCount shouldBe 0
        smart.executionCount shouldBe 1
        PlayAutoFallbackPolicy.mayFallbackAfter(
            PlayAutoFallbackPolicy.Stage.PROVIDER_UNAVAILABLE,
        ) shouldBe true
    }

    "D. unavailable preferred with no fallback returns PROVIDER_UNAVAILABLE" {
        val youtube = FakeMediaAdapter.youtube(available = false)
        engine(youtube).execute(request(preferred = MediaProvider.YOUTUBE))
            .asFailure().reason shouldBe PlaybackFailureReason.PROVIDER_UNAVAILABLE
        youtube.executionCount shouldBe 0
    }

    "E. DIRECT_PLAY + SEARCH + OPEN_APP selects DIRECT_PLAY" {
        val adapter = FakeMediaAdapter.smartTube(
            capabilities = setOf(
                PlaybackCapability.DIRECT_PLAY,
                PlaybackCapability.SEARCH,
                PlaybackCapability.OPEN_APP,
            ),
        )
        engine(adapter).execute(request()).asSuccess().strategy shouldBe
            PlaybackStrategy.DIRECT_PLAY
    }

    "E. SEARCH + OPEN_APP selects SEARCH" {
        val adapter = FakeMediaAdapter.youtube(
            capabilities = setOf(PlaybackCapability.SEARCH, PlaybackCapability.OPEN_APP),
        )
        engine(adapter).execute(request()).asSuccess().strategy shouldBe
            PlaybackStrategy.SEARCH
    }

    "E. OPEN_APP only selects OPEN_APP" {
        val adapter = FakeMediaAdapter.youtube(
            capabilities = setOf(PlaybackCapability.OPEN_APP),
        )
        engine(adapter).execute(request()).asSuccess().strategy shouldBe
            PlaybackStrategy.OPEN_APP
    }

    "F. AUDIO against VIDEO-only provider is not executed" {
        val videoOnly = FakeMediaAdapter.youtube().apply {
            supportedMediaTypes = setOf(MediaType.VIDEO)
        }
        engine(videoOnly).execute(request(type = MediaType.AUDIO))
            .asFailure().reason shouldBe PlaybackFailureReason.UNSUPPORTED_MEDIA
        videoOnly.executionCount shouldBe 0
        videoOnly.resolveCount shouldBe 0
    }

    "G. resolution failure before execute allows deterministic fallback" {
        val youtube = FakeMediaAdapter.youtube().apply { resolveSucceeds = false }
        val smart = FakeMediaAdapter.smartTube()
        val result = engine(youtube, smart).execute(
            request(preferred = MediaProvider.YOUTUBE),
        ).asSuccess()
        result.provider shouldBe MediaProvider.SMARTTUBE
        youtube.executionCount shouldBe 0
        youtube.resolveCount shouldBe 1
        smart.executionCount shouldBe 1
        PlayAutoFallbackPolicy.mayFallbackAfter(
            PlayAutoFallbackPolicy.Stage.RESOLUTION_FAILED,
        ) shouldBe true
    }

    "H. execution failure does not fallback to another provider" {
        val youtube = FakeMediaAdapter.youtube().apply { executeSucceeds = false }
        val smart = FakeMediaAdapter.smartTube()
        val result = engine(youtube, smart).execute(
            request(preferred = MediaProvider.YOUTUBE),
        ).asFailure()
        result.reason shouldBe PlaybackFailureReason.EXECUTION_FAILED
        youtube.executionCount shouldBe 1
        smart.executionCount shouldBe 0
        PlayAutoFallbackPolicy.mayFallbackAfter(
            PlayAutoFallbackPolicy.Stage.EXECUTION_ATTEMPTED,
        ) shouldBe false
    }

    "I. one execute produces at most one successful adapter execution" {
        val youtube = FakeMediaAdapter.youtube()
        val smart = FakeMediaAdapter.smartTube()
        val music = FakeMediaAdapter.musicLoop()
        val result = engine(youtube, smart, music).execute(
            request(preferred = MediaProvider.YOUTUBE),
        ).asSuccess()
        result.provider shouldBe MediaProvider.YOUTUBE
        val accepted = listOf(youtube, smart, music).sumOf { it.executionCount }
        accepted shouldBe 1
        accepted shouldBeLessThanOrEqual 1
    }

    "J. new Fake provider is routed without engine source changes" {
        val vlc = FakeMediaAdapter(
            provider = MediaProvider("vlc"),
            capabilities = setOf(PlaybackCapability.DIRECT_PLAY),
        )
        val result = engine(vlc).execute(request()).asSuccess()
        result.provider.id shouldBe "vlc"
        result.strategy shouldBe PlaybackStrategy.DIRECT_PLAY
        vlc.executionCount shouldBe 1
    }

    "K. empty registry is typed failure and does not crash" {
        val result = PlayAutoEngine(ProviderRegistry()).execute(request())
        result.asFailure().reason shouldBe PlaybackFailureReason.PROVIDER_NOT_FOUND
    }

    "preferred missing from registry falls back to registered adapters" {
        val smart = FakeMediaAdapter.smartTube()
        val result = engine(smart).execute(request(preferred = MediaProvider.YOUTUBE)).asSuccess()
        result.provider shouldBe MediaProvider.SMARTTUBE
        smart.executionCount shouldBe 1
    }

    "DEEP_LINK beats SEARCH when DIRECT_PLAY is absent" {
        val adapter = FakeMediaAdapter.youtube(
            capabilities = setOf(
                PlaybackCapability.DEEP_LINK,
                PlaybackCapability.SEARCH,
                PlaybackCapability.OPEN_APP,
            ),
        )
        engine(adapter).execute(request()).asSuccess().strategy shouldBe
            PlaybackStrategy.DEEP_LINK
    }

    "empty capabilities yield NO_PLAYBACK_STRATEGY and no execute" {
        val adapter = FakeMediaAdapter.youtube(capabilities = emptySet())
        engine(adapter).execute(request()).asFailure().reason shouldBe
            PlaybackFailureReason.NO_PLAYBACK_STRATEGY
        adapter.executionCount shouldBe 0
    }

    "PlayAutoFallbackPolicy documents the execute boundary" {
        PlayAutoFallbackPolicy.mayFallbackAfter(
            PlayAutoFallbackPolicy.Stage.RESOLUTION_FAILED,
        ) shouldBe true
        PlayAutoFallbackPolicy.mayFallbackAfter(
            PlayAutoFallbackPolicy.Stage.EXECUTION_ATTEMPTED,
        ) shouldBe false
    }
})
