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
    val resolverBaseUrlConfigured: Boolean = false,
    val resolverRequestAttempted: Boolean = false,
    val resolverStatus: String? = null,
    val resolverHttpStatus: Int? = null,
    val resolvedChannelTitle: String? = null,
    val resolverCache: String? = null,
    val resolverLatencyMs: Long? = null,
    val resolverHttps: Boolean? = null,
    val resolverCleartextHttp: Boolean = false,
    val httpsUnavailableNote: String? = null,
) {
    fun formatHarness(): String = buildString {
        appendLine("Query: $query")
        appendLine("Resolver base URL configured: ${yesNo(resolverBaseUrlConfigured)}")
        appendLine("Resolver request attempted: ${yesNo(resolverRequestAttempted)}")
        appendLine("Resolver status: ${resolverStatus ?: "NONE"}")
        appendLine("HTTP status: ${resolverHttpStatus?.toString() ?: "NONE"}")
        appendLine("Resolver success: ${yesNo(resolverSuccess)}")
        appendLine("Resolved videoId: ${resolvedVideoId ?: "NONE"}")
        appendLine("Resolved title: ${resolvedTitle ?: "NONE"}")
        appendLine("Resolved channel: ${resolvedChannelTitle ?: "NONE"}")
        appendLine("Resolved watchUrl: ${targetUri ?: "NONE"}")
        appendLine("Resolver cache: ${resolverCache ?: "NONE"}")
        appendLine("Resolver latency: ${resolverLatencyMs?.let { "${it}ms" } ?: "NONE"}")
        appendLine("HTTPS used: ${resolverHttps?.let { yesNo(it) } ?: "NONE"}")
        if (resolverCleartextHttp || !httpsUnavailableNote.isNullOrBlank()) {
            appendLine("HTTPS unavailable: ${httpsUnavailableNote ?: "cleartext HTTP configured for harness/dev only"}")
        }
        appendLine("Resolution method: ${resolutionMethod ?: "NONE"}")
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
