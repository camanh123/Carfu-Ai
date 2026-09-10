package org.stypox.dicio.playauto.adapter

import org.stypox.dicio.playauto.core.MediaRequest
import org.stypox.dicio.playauto.core.MediaTarget
import org.stypox.dicio.playauto.core.MediaType
import org.stypox.dicio.playauto.core.PlaybackCapability
import org.stypox.dicio.playauto.core.PlaybackFailureReason
import org.stypox.dicio.playauto.core.PlaybackStrategy
import org.stypox.dicio.playauto.provider.MediaProvider

/**
 * Dry-run adapter. Never launches an application.
 * Test fixtures only — not real-device capabilities.
 */
class FakeMediaAdapter(
    override val provider: MediaProvider,
    override var available: Boolean = true,
    var supportedMediaTypes: Set<MediaType> = setOf(
        MediaType.AUDIO,
        MediaType.VIDEO,
        MediaType.UNKNOWN,
    ),
    override var capabilities: Set<PlaybackCapability> = setOf(
        PlaybackCapability.SEARCH,
        PlaybackCapability.OPEN_APP,
    ),
    var resolveSucceeds: Boolean = true,
    var executeSucceeds: Boolean = true,
) : MediaAdapter {
    var lastRequest: MediaRequest? = null
    var lastTarget: MediaTarget? = null
    var lastStrategy: PlaybackStrategy? = null
    var resolveCount: Int = 0
    var executionCount: Int = 0

    override fun canHandle(request: MediaRequest): Boolean =
        available && request.mediaType in supportedMediaTypes

    override fun resolve(request: MediaRequest): ResolveOutcome {
        resolveCount += 1
        lastRequest = request
        if (!resolveSucceeds) {
            return ResolveOutcome.Failed(PlaybackFailureReason.RESOLUTION_FAILED)
        }
        val target = MediaTarget(
            query = request.query,
            mediaType = request.mediaType,
            provider = provider,
            descriptor = "fake:${provider.id}:${request.query}",
        )
        lastTarget = target
        return ResolveOutcome.Ok(target)
    }

    override fun execute(target: MediaTarget, strategy: PlaybackStrategy): ExecuteOutcome {
        executionCount += 1
        lastTarget = target
        lastStrategy = strategy
        return if (executeSucceeds) {
            ExecuteOutcome.Accepted(strategy)
        } else {
            ExecuteOutcome.Failed(PlaybackFailureReason.EXECUTION_FAILED)
        }
    }

    companion object {
        /** TEST FIXTURE. Not a real-device YouTube capability claim. */
        fun youtube(
            available: Boolean = true,
            capabilities: Set<PlaybackCapability> = setOf(
                PlaybackCapability.SEARCH,
                PlaybackCapability.OPEN_APP,
            ),
        ): FakeMediaAdapter = FakeMediaAdapter(
            provider = MediaProvider.YOUTUBE,
            available = available,
            capabilities = capabilities,
        )

        /** TEST FIXTURE. Not a real-device SmartTube capability claim. */
        fun smartTube(
            available: Boolean = true,
            capabilities: Set<PlaybackCapability> = setOf(
                PlaybackCapability.DIRECT_PLAY,
                PlaybackCapability.SEARCH,
                PlaybackCapability.OPEN_APP,
            ),
        ): FakeMediaAdapter = FakeMediaAdapter(
            provider = MediaProvider.SMARTTUBE,
            available = available,
            capabilities = capabilities,
        )

        /** TEST FIXTURE. Not a real-device MusicLoop capability claim. */
        fun musicLoop(
            available: Boolean = true,
            capabilities: Set<PlaybackCapability> = setOf(
                PlaybackCapability.DIRECT_PLAY,
                PlaybackCapability.SEARCH,
            ),
        ): FakeMediaAdapter = FakeMediaAdapter(
            provider = MediaProvider.MUSICLOOP,
            available = available,
            capabilities = capabilities,
        )
    }
}
