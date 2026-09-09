package org.stypox.dicio.youtubeplayauto

/**
 * App-specific PlayAuto driver. YouTube is implemented now; SmartTube / MusicLoop later.
 * Not a second PlayAutoEngine and not Vietnamese NLU.
 */
interface AppPlayAutoDriver {
    val targetAppId: String
    fun canHandle(request: PlayAutoRequest): Boolean
    fun execute(request: PlayAutoRequest, mode: YouTubeLaunchMode): YouTubePlayAutoResult
}

object YouTubeTargetApps {
    /** Identity matching for an already-selected provider. Not speech parsing. */
    fun isYouTube(targetApp: String): Boolean {
        val folded = targetApp.trim().lowercase().replace(" ", "")
        return folded == "youtube" || folded == "yt" || folded == "youtubeapp"
    }
}

data class YouTubePlayAutoResult(
    val targetApp: String,
    val query: String,
    val searchOpened: Boolean,
    val resultSelected: Boolean,
    val playbackRequested: Boolean,
    val matchedTitle: String? = null,
    val failure: String? = null,
    /** On failure after search, YouTube is left as opened. Never closed by PlayAuto. */
    val youtubeLeftOpen: Boolean = false,
    val searchDispatchCount: Int = 0,
    val selectAttemptCount: Int = 0,
)
