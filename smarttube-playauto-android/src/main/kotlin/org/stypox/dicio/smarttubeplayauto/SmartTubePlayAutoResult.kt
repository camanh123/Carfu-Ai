package org.stypox.dicio.smarttubeplayauto

data class SmartTubePlayAutoResult(
    val targetApp: String,
    val query: String,
    val resolvedVideoId: String? = null,
    val resolvedTitle: String? = null,
    val targetUri: String? = null,
    val resolverSuccess: Boolean = false,
    val resolverStatus: String? = null,
    val resolverRequestAttempted: Boolean = false,
    val resolverBaseUrlConfigured: Boolean = false,
    val resolverHttpStatus: Int? = null,
    val resolvedChannelTitle: String? = null,
    val resolverCache: String? = null,
    val resolverLatencyMs: Long? = null,
    val resolverHttps: Boolean? = null,
    val installedPackages: List<String> = emptyList(),
    val launchForm: SmartTubeLaunchForm? = null,
    val intentAction: String? = null,
    val intentUri: String? = null,
    val targetPackage: String? = null,
    val resolveActivity: String? = null,
    val resolveActivityPackage: String? = null,
    val exactVideoTargetRequested: Boolean? = null,
    val launchAttempted: Boolean = false,
    val launchResult: String? = null,
    val youtubeFallbackUsed: Boolean = false,
    val accessibilityUsed: Boolean = false,
    val castApisUsed: Boolean = false,
    val mediaKeysSent: Boolean = false,
    val path: String = "NONE",
    val failure: String? = null,
    val packageEvidence: String = SmartTubeCatalog.PACKAGE_EVIDENCE,
    val intentEvidence: String = SmartTubeLaunchAudit.EVIDENCE,
) {
    fun formatHarness(): String = buildString {
        appendLine("Query: $query")
        appendLine("Target app: $targetApp")
        appendLine("Installed SmartTube package(s): ${installedPackages.ifEmpty { listOf("NONE") }.joinToString()}")
        appendLine("Package evidence: $packageEvidence")
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
        appendLine("Launch form: ${launchForm?.name ?: "NONE"}")
        appendLine("SELECTED_PACKAGE: ${targetPackage ?: "NONE"}")
        appendLine("INTENT_ACTION: ${intentAction ?: "NONE"}")
        appendLine("INTENT_URI: ${intentUri ?: "NONE"}")
        appendLine("RESOLVE_ACTIVITY: ${resolveActivity ?: "NONE"}")
        appendLine("RESOLVE_ACTIVITY_PACKAGE: ${resolveActivityPackage ?: "NONE"}")
        appendLine("EXACT_VIDEO_TARGET_REQUESTED: ${exactVideoTargetRequested?.let { yesNo(it) } ?: "NONE"}")
        appendLine("LAUNCH_ATTEMPTED: ${yesNo(launchAttempted)}")
        appendLine("LAUNCH_RESULT: ${launchResult ?: "NONE"}")
        appendLine("Path: $path")
        appendLine("Failure: ${failure ?: "NONE"}")
        appendLine("Intent evidence: $intentEvidence")
        appendLine("YouTube fallback used: ${yesNo(youtubeFallbackUsed)}")
        appendLine("Accessibility used: NO")
        appendLine("Cast APIs used: NO")
        appendLine("Media keys sent: NO")
        appendLine("OPENED_SMARTTUBE: human device observation only")
        appendLine("OPENED_EXACT_VIDEO: human device observation only")
        appendLine("AUTOPLAY_STARTED: human device observation only")
        appendLine("PLAYBACK_CONFIRMED: not claimed")
        appendLine("DEVICE PASS: not claimed")
    }

    private fun yesNo(value: Boolean): String = if (value) "YES" else "NO"
}
