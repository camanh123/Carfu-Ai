package org.stypox.dicio.smarttubeplayauto

import org.stypox.dicio.youtubeplayauto.YouTubeLaunchMode

data class SmartTubeResolveActivity(
    val component: String?,
    val packageName: String?,
)

sealed class SmartTubeLaunchOutcome {
    data class DryRun(
        val spec: SmartTubeLaunchSpec,
        val resolveActivity: SmartTubeResolveActivity?,
    ) : SmartTubeLaunchOutcome()

    data class Dispatched(
        val spec: SmartTubeLaunchSpec,
        val resolveActivity: SmartTubeResolveActivity?,
    ) : SmartTubeLaunchOutcome()

    data class Failed(
        val detail: String,
        val spec: SmartTubeLaunchSpec? = null,
        val resolveActivity: SmartTubeResolveActivity? = null,
    ) : SmartTubeLaunchOutcome()

    data class Refused(
        val detail: String,
        val spec: SmartTubeLaunchSpec? = null,
        val resolveActivity: SmartTubeResolveActivity? = null,
    ) : SmartTubeLaunchOutcome()
}

/**
 * SmartTube-only launcher. Never launches YouTube. Never uses Accessibility.
 */
interface SmartTubeLauncher {
    fun installedPackages(): List<SmartTubeInstalledPackage>

    fun preferredInstalledPackage(): String? {
        val selectable = SmartTubeTargetSelection.selectable(installedPackages())
        return selectable.singleOrNull()?.packageName
    }

    fun resolveActivity(spec: SmartTubeLaunchSpec): SmartTubeResolveActivity?

    fun launch(spec: SmartTubeLaunchSpec, mode: YouTubeLaunchMode): SmartTubeLaunchOutcome
}
