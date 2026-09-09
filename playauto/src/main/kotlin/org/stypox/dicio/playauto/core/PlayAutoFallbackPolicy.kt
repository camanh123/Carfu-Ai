package org.stypox.dicio.playauto.core

/**
 * Fallback is allowed only before an adapter [execute] call.
 *
 * After [org.stypox.dicio.playauto.adapter.MediaAdapter.execute] is invoked,
 * the engine MUST NOT try another provider. That is the exactly-once /
 * no-dual-launch boundary.
 */
object PlayAutoFallbackPolicy {
    enum class Stage {
        PROVIDER_UNAVAILABLE,
        UNSUPPORTED_MEDIA,
        NO_PLAYBACK_STRATEGY,
        RESOLUTION_FAILED,
        EXECUTION_ATTEMPTED,
    }

    fun mayFallbackAfter(stage: Stage): Boolean = when (stage) {
        Stage.PROVIDER_UNAVAILABLE,
        Stage.UNSUPPORTED_MEDIA,
        Stage.NO_PLAYBACK_STRATEGY,
        Stage.RESOLUTION_FAILED,
        -> true
        Stage.EXECUTION_ATTEMPTED -> false
    }
}
