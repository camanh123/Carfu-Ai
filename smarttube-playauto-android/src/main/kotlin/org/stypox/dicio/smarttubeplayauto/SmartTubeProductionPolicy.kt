package org.stypox.dicio.smarttubeplayauto

/**
 * Production-approved SmartTube PlayAuto policy. Device-proven Probe A only:
 * ACTION_VIEW + watch URL pinned to [PACKAGE].
 *
 * org.smarttube.beta and the diagnostic harness are never production targets.
 */
object SmartTubeProductionPolicy {
    const val PACKAGE = SmartTubeCatalog.ORG_SMARTTUBE_STABLE
    val LAUNCH_FORM: SmartTubeLaunchForm = SmartTubeLaunchForm.VIEW_WATCH_URL_PINNED

    fun options(): SmartTubePlayAutoOptions = SmartTubePlayAutoOptions(
        launchForm = LAUNCH_FORM,
        selectedPackage = PACKAGE,
    )

    fun isApprovedTarget(packageName: String?): Boolean =
        packageName?.trim() == PACKAGE

    fun isForbiddenTarget(packageName: String?): Boolean {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty()) return false
        if (SmartTubeHarnessIdentity.isHarness(pkg)) return true
        if (pkg == SmartTubeCatalog.ORG_SMARTTUBE_BETA) return true
        if (ForbiddenYouTubePackages.isYouTube(pkg)) return true
        return pkg != PACKAGE
    }

    fun unavailableFailure(raw: String?): String = when (raw) {
        "selected_package_not_device_installed",
        "smarttube_unavailable",
        "package_selection_required",
        -> "smarttube_unavailable"
        else -> raw ?: "smarttube_unavailable"
    }
}
