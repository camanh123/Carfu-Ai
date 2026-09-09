package org.stypox.dicio.playauto.provider

/**
 * Logical provider identity. Not an Android package, Intent, or app launch spec.
 * New providers are additional ids — the engine does not switch on these constants.
 */
data class MediaProvider(val id: String) {
    init {
        require(id.isNotBlank()) { "provider id must not be blank" }
    }

    companion object {
        val YOUTUBE = MediaProvider("youtube")
        val SMARTTUBE = MediaProvider("smarttube")
        val MUSICLOOP = MediaProvider("musicloop")
    }
}
