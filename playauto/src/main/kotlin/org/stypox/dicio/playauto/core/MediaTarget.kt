package org.stypox.dicio.playauto.core

import org.stypox.dicio.playauto.provider.MediaProvider

/**
 * Adapter-resolved playback target. [descriptor] is opaque to the engine
 * (no Android Intent / package knowledge).
 */
data class MediaTarget(
    val query: String,
    val mediaType: MediaType,
    val provider: MediaProvider,
    val descriptor: String,
)
