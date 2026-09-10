package org.stypox.dicio.playauto.core

import org.stypox.dicio.playauto.adapter.ExecuteOutcome
import org.stypox.dicio.playauto.adapter.ResolveOutcome
import org.stypox.dicio.playauto.capability.CapabilityResolver
import org.stypox.dicio.playauto.provider.ProviderRegistry
import org.stypox.dicio.playauto.provider.ProviderResolver

/**
 * Standalone PlayAuto core. Accepts [MediaRequest] only — never raw speech.
 *
 * Not connected to Voice, NLU, SkillEvaluator, CanonicalCommand, or Android.
 *
 * Provider resolution:
 * 1. Reject blank/whitespace [MediaRequest.query] as [PlaybackFailureReason.INVALID_REQUEST].
 * 2. Candidate adapters from [ProviderResolver] (preferred, else default, then registry order).
 * 3. Skip unavailable / incapable / unresolvable adapters (fallback allowed).
 * 4. First successful [org.stypox.dicio.playauto.adapter.MediaAdapter.execute] wins and stops.
 * 5. If execute() is invoked, no further adapter is tried
 *    ([PlayAutoFallbackPolicy.Stage.EXECUTION_ATTEMPTED]).
 */
class PlayAutoEngine(
    private val registry: ProviderRegistry,
    private val providerResolver: ProviderResolver = ProviderResolver(registry),
    private val capabilityResolver: CapabilityResolver = CapabilityResolver(),
    private val strategySelector: PlaybackStrategySelector = PlaybackStrategySelector,
) {
    fun execute(request: MediaRequest): PlaybackResult {
        val query = request.query.trim()
        if (query.isEmpty()) {
            return PlaybackResult.Failure(PlaybackFailureReason.INVALID_REQUEST)
        }
        val normalized = request.copy(query = query)
        if (registry.isEmpty()) {
            return PlaybackResult.Failure(PlaybackFailureReason.PROVIDER_NOT_FOUND)
        }

        var lastFailure = PlaybackFailureReason.PROVIDER_NOT_FOUND
        val candidates = providerResolver.candidates(normalized)
        if (candidates.isEmpty()) {
            return PlaybackResult.Failure(PlaybackFailureReason.PROVIDER_NOT_FOUND)
        }

        for (adapter in candidates) {
            if (!adapter.available) {
                lastFailure = PlaybackFailureReason.PROVIDER_UNAVAILABLE
                continue
            }
            if (!adapter.canHandle(normalized)) {
                lastFailure = PlaybackFailureReason.UNSUPPORTED_MEDIA
                continue
            }
            val capabilities = capabilityResolver.resolve(adapter, normalized)
            val strategy = strategySelector.select(capabilities)
            if (strategy == null) {
                lastFailure = PlaybackFailureReason.NO_PLAYBACK_STRATEGY
                continue
            }
            when (val resolved = adapter.resolve(normalized)) {
                is ResolveOutcome.Failed -> {
                    lastFailure = resolved.reason
                    continue
                }
                is ResolveOutcome.Ok -> {
                    // Fallback boundary: execute() has been invoked.
                    return when (val executed = adapter.execute(resolved.target, strategy)) {
                        is ExecuteOutcome.Accepted -> PlaybackResult.Success(
                            provider = adapter.provider,
                            strategy = executed.strategy,
                            target = resolved.target,
                        )
                        is ExecuteOutcome.Failed -> PlaybackResult.Failure(
                            reason = executed.reason,
                            detail = adapter.provider.id,
                        )
                    }
                }
            }
        }
        return PlaybackResult.Failure(lastFailure)
    }
}
