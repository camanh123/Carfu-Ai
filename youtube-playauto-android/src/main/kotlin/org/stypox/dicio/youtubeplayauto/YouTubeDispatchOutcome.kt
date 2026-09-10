package org.stypox.dicio.youtubeplayauto

/**
 * Highest state that can be claimed after a launch attempt.
 *
 * [INTENT_DISPATCHED] is the maximum this phase can prove: startActivity returned.
 * That is not [TARGET_OPENED] and not [PLAYBACK_CONFIRMED].
 */
enum class YouTubeProvenance {
    DRY_RUN_SELECTED,
    INTENT_DISPATCHED,
    TARGET_OPENED,
    PLAYBACK_CONFIRMED,
}

sealed class YouTubeDispatchOutcome {
    data class DryRun(val spec: YouTubeLaunchSpec) : YouTubeDispatchOutcome() {
        val provenance: YouTubeProvenance = YouTubeProvenance.DRY_RUN_SELECTED
    }

    data class Dispatched(
        val spec: YouTubeLaunchSpec,
        val provenance: YouTubeProvenance = YouTubeProvenance.INTENT_DISPATCHED,
    ) : YouTubeDispatchOutcome()

    data class Failed(val detail: String) : YouTubeDispatchOutcome()
}
