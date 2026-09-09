package org.stypox.dicio.youtubeplayauto

/**
 * Structured PlayAuto input. Not raw speech. NLU / app selection already happened upstream.
 */
data class PlayAutoRequest(
    val targetApp: String,
    val query: String,
)
