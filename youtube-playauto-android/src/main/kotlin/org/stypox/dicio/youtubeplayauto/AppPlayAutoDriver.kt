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
    val diagnostics: YouTubePlayAutoDiagnostics? = null,
    val resolvedVideoId: String? = null,
    val resolvedTitle: String? = null,
    val resolutionMethod: String? = null,
    val targetUri: String? = null,
    val resolverSuccess: Boolean = false,
    val launchAttempted: Boolean = false,
    val launchResult: String? = null,
    val accessibilityFallbackUsed: Boolean = false,
    val castApisUsed: Boolean = false,
    val mediaKeysSent: Boolean = false,
    val path: String = "NONE",
) {
    fun formatHarness(): String = buildString {
        appendLine("Resolver success: ${yesNo(resolverSuccess)}")
        appendLine("Resolved video id: ${resolvedVideoId ?: "NONE"}")
        appendLine("Resolved title: ${resolvedTitle ?: "NONE"}")
        appendLine("Resolution method: ${resolutionMethod ?: "NONE"}")
        appendLine("Target URI: ${targetUri ?: "NONE"}")
        appendLine("Launch attempted: ${yesNo(launchAttempted)}")
        appendLine("Launch result: ${launchResult ?: "NONE"}")
        appendLine("Path: $path")
        appendLine("Accessibility fallback used: ${yesNo(accessibilityFallbackUsed)}")
        appendLine("Cast APIs used: NO")
        appendLine("Media keys sent: NO")
        appendLine("PLAYBACK_CONFIRMED: not claimed from watch-URL launch")
        appendLine("Cast TV triggered: observe on device (YouTube-side if yes)")
    }

    private fun yesNo(value: Boolean): String = if (value) "YES" else "NO"
}
