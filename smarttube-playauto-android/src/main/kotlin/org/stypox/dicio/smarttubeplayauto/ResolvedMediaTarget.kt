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
    val installed: Boolean = true,
    val applicationLabel: String? = null,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val enabled: Boolean? = null,
    val launchIntent: String? = null,
    val launchActivity: String? = null,
    val resolveActivity: String? = null,
    val evidence: Set<String> = emptySet(),
    val source: String = "unknown",
) {
    fun formatBlock(): String = buildString {
        appendLine("PACKAGE=$packageName")
        appendLine("INSTALLED=${if (installed) "true" else "false"}")
        appendLine("APPLICATION_LABEL=${applicationLabel ?: "NONE"}")
        appendLine("VERSION_NAME=${versionName ?: "NONE"}")
        appendLine("VERSION_CODE=${versionCode?.toString() ?: "NONE"}")
        appendLine("ENABLED=${enabled?.toString() ?: "NONE"}")
        appendLine("LAUNCH_INTENT=${launchIntent ?: "NONE"}")
        appendLine("LAUNCH_ACTIVITY=${launchActivity ?: "NONE"}")
        appendLine("RESOLVE_ACTIVITY=${resolveActivity ?: "NONE"}")
        appendLine(
            "EVIDENCE=${
                if (evidence.isEmpty()) "NONE" else evidence.sorted().joinToString(",")
            }",
        )
    }
}

data class SmartTubePlayAutoOptions(
    /**
     * Null means PlayAuto must not guess a launch form. Source does not prove
     * SmartTube Intent behavior; the diagnostic harness probes forms one at a time.
     */
    val launchForm: SmartTubeLaunchForm? = null,
    /**
     * Explicit DEVICE_INSTALLED SmartTube package for probes. Required when more
     * than one installed candidate exists. Never the diagnostic harness.
     */
    val selectedPackage: String? = null,
)
