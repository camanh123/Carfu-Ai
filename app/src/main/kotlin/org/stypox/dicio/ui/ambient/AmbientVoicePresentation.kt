package org.stypox.dicio.ui.ambient

import org.stypox.dicio.io.session.CommandSessionPhase

/**
 * Pure V2-UI-1 Ambient Voice presentation.
 * Observes voice state only — never creates or restarts a VoiceSession.
 */
data class AmbientVoiceUiState(
    val visible: Boolean,
    /** Raw STT live transcript only; null/blank before speech. */
    val rawTranscript: String? = null,
)

object AmbientVoicePresentation {
    /** Fade duration for show/hide (ms). */
    const val FADE_MS = 200

    fun fromSession(
        phase: CommandSessionPhase,
        rawPartialTranscript: String?,
    ): AmbientVoiceUiState {
        val listening = isListeningPhase(phase)
        if (!listening) {
            return AmbientVoiceUiState(visible = false, rawTranscript = null)
        }
        val raw = rawPartialTranscript?.trim()?.takeIf { it.isNotEmpty() }
        return AmbientVoiceUiState(visible = true, rawTranscript = raw)
    }

    fun isListeningPhase(phase: CommandSessionPhase): Boolean =
        phase == CommandSessionPhase.WAKE_DETECTED ||
            phase == CommandSessionPhase.COMMAND_LISTENING

    /**
     * Overlay / Compose observers must never call this for session control.
     * Documented invariant for tests.
     */
    fun mayCreateOrRestartVoiceSession(): Boolean = false
}
