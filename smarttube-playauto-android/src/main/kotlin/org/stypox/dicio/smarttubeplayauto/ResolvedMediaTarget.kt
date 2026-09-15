package org.stypox.dicio.smarttubeplayauto

/**
 * Provider-independent YouTube content id resolved by the frozen CARFU resolver.
 * SmartTube owns only how this target is opened.
 */
data class ResolvedMediaTarget(
    val videoId: String,
    val resolvedTitle: String?,
    val watchUrl: String,
)

data class SmartTubeInstalledPackage(
    val packageName: String,
    val versionName: String? = null,
    val launchActivity: String? = null,
    val source: String,
)

data class SmartTubePlayAutoOptions(
    /**
     * Null means PlayAuto must not guess a launch form. Source does not prove
     * SmartTube Intent behavior; the diagnostic harness probes forms one at a time.
     */
    val launchForm: SmartTubeLaunchForm? = null,
)
