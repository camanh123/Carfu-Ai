package org.stypox.dicio.playauto.core

import org.stypox.dicio.playauto.provider.MediaProvider

/**
 * Structured playback request. PlayAuto never accepts or parses raw speech.
 */
data class MediaRequest(
    val query: String,
    val mediaType: MediaType,
    val preferredProvider: MediaProvider? = null,
)
