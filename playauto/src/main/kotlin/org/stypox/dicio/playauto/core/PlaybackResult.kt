package org.stypox.dicio.playauto.core

import org.stypox.dicio.playauto.provider.MediaProvider

enum class PlaybackFailureReason {
    INVALID_REQUEST,
    PROVIDER_NOT_FOUND,
    PROVIDER_UNAVAILABLE,
    UNSUPPORTED_MEDIA,
    NO_PLAYBACK_STRATEGY,
    RESOLUTION_FAILED,
    EXECUTION_FAILED,
}

sealed class PlaybackResult {
    data class Success(
        val provider: MediaProvider,
        val strategy: PlaybackStrategy,
        val target: MediaTarget,
    ) : PlaybackResult()

    data class Failure(
        val reason: PlaybackFailureReason,
        val detail: String = "",
    ) : PlaybackResult()
}
