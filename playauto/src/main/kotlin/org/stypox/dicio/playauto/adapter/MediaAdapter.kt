package org.stypox.dicio.playauto.adapter

import org.stypox.dicio.playauto.core.MediaRequest
import org.stypox.dicio.playauto.core.MediaTarget
import org.stypox.dicio.playauto.core.PlaybackCapability
import org.stypox.dicio.playauto.core.PlaybackFailureReason
import org.stypox.dicio.playauto.core.PlaybackStrategy
import org.stypox.dicio.playauto.provider.MediaProvider

sealed class ResolveOutcome {
    data class Ok(val target: MediaTarget) : ResolveOutcome()
    data class Failed(val reason: PlaybackFailureReason) : ResolveOutcome()
}

sealed class ExecuteOutcome {
    data class Accepted(val strategy: PlaybackStrategy) : ExecuteOutcome()
    data class Failed(val reason: PlaybackFailureReason) : ExecuteOutcome()
}

/**
 * Provider-facing playback port. The engine depends only on this interface.
 * Implementations must not leak Android types into the core.
 */
interface MediaAdapter {
    val provider: MediaProvider
    val available: Boolean
    val capabilities: Set<PlaybackCapability>

    fun canHandle(request: MediaRequest): Boolean
    fun resolve(request: MediaRequest): ResolveOutcome
    fun execute(target: MediaTarget, strategy: PlaybackStrategy): ExecuteOutcome
}
