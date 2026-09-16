package org.stypox.dicio.smarttubeplayauto

/**
 * Catalog package ids copied from CARFU OpenApp / InstalledAppResolver.
 *
 * SOURCE-PROVEN as catalog candidates only. Not a CARFU-device install proof.
 * Do not treat the first entry as "the" SmartTube package on the head unit.
 */
object SmartTubeCatalog {
    const val TEAMSMART = "com.teamsmart.videomanager.tv"
    const val LISKOVSOFT_SMARTTUBE = "com.liskovsoft.smarttube.tv"
    const val LISKOVSOFT_SMARTYOUTUBE = "com.liskovsoft.smartyoutubetv2"

    /** Device-scan packages that still need PackageManager verification. Not catalog proof. */
    const val ORG_SMARTTUBE_BETA = "org.smarttube.beta"
    const val ORG_SMARTTUBE_STABLE = "org.smarttube.stable"

    val CATALOG_PACKAGES: List<String> = listOf(
        TEAMSMART,
        LISKOVSOFT_SMARTTUBE,
        LISKOVSOFT_SMARTYOUTUBE,
    )

    val DEVICE_QUERY_PACKAGES: List<String> = listOf(
        ORG_SMARTTUBE_BETA,
        ORG_SMARTTUBE_STABLE,
    )

    val QUERY_PACKAGES: List<String> = CATALOG_PACKAGES + DEVICE_QUERY_PACKAGES

    const val PACKAGE_EVIDENCE = "catalog_only_not_device_proven"
}

object SmartTubeHarnessIdentity {
    const val PACKAGE = "org.stypox.dicio.smarttubeplayauto"

    fun isHarness(packageName: String?): Boolean {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty()) return false
        return pkg == PACKAGE || pkg.startsWith("$PACKAGE.")
    }
}

object SmartTubeEvidence {
    const val CATALOG_CANDIDATE = "CATALOG_CANDIDATE"
    const val DEVICE_INSTALLED = "DEVICE_INSTALLED"
    const val DEVICE_LAUNCHABLE = "DEVICE_LAUNCHABLE"
    const val DEVICE_LABEL_MATCH = "DEVICE_LABEL_MATCH"
}

object SmartTubeTargetApps {
    /** Identity matching for an already-selected provider. Not speech parsing. */
    fun isSmartTube(targetApp: String): Boolean {
        val folded = targetApp.trim().lowercase().replace(" ", "")
        return folded == "smarttube"
    }
}

/**
 * Packages this driver must never launch. Copied as a deny-list; YouTube PlayAuto
 * is not modified.
 */
object ForbiddenYouTubePackages {
    val PACKAGES: Set<String> = setOf(
        "com.google.android.youtube",
        "com.google.android.youtube.tv",
        "com.vanced.android.youtube",
        "app.revanced.android.youtube",
    )

    fun isYouTube(packageName: String?): Boolean {
        val pkg = packageName?.trim().orEmpty()
        return pkg.isNotEmpty() && pkg in PACKAGES
    }
}

object SmartTubePackageNames {
    fun looksLikeSmartTube(packageName: String): Boolean {
        if (SmartTubeHarnessIdentity.isHarness(packageName)) return false
        val n = packageName.trim().lowercase()
        if (n.isEmpty()) return false
        if (n in SmartTubeCatalog.QUERY_PACKAGES) return true
        return n.contains("smarttube") || n.contains("smartyoutubetv")
    }

    fun labelMatchesSmartTube(label: String?): Boolean {
        val folded = label?.trim()?.lowercase()?.replace(" ", "").orEmpty()
        if (folded.isEmpty()) return false
        return folded.contains("smarttube")
    }

    fun isSafeSmartTubeTarget(packageName: String?): Boolean {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty()) return false
        if (SmartTubeHarnessIdentity.isHarness(pkg)) return false
        if (ForbiddenYouTubePackages.isYouTube(pkg)) return false
        return looksLikeSmartTube(pkg)
    }
}

sealed class SmartTubePinDecision {
    data class Selected(val packageName: String) : SmartTubePinDecision()
    object None : SmartTubePinDecision()
    data class Ambiguous(val packages: List<String>) : SmartTubePinDecision()
    data class Rejected(val reason: String) : SmartTubePinDecision()
}

/**
 * Probe targeting policy. Never silently picks among multiple DEVICE_INSTALLED
 * packages. Never selects the diagnostic harness.
 */
object SmartTubeTargetSelection {
    fun selectable(packages: List<SmartTubeInstalledPackage>): List<SmartTubeInstalledPackage> =
        packages.filter { pkg ->
            pkg.installed &&
                SmartTubePackageNames.isSafeSmartTubeTarget(pkg.packageName)
        }.distinctBy { it.packageName }

    fun pin(
        packages: List<SmartTubeInstalledPackage>,
        explicitPackage: String?,
    ): SmartTubePinDecision {
        val selectable = selectable(packages)
        val explicit = explicitPackage?.trim()?.ifEmpty { null }
        if (explicit != null) {
            if (SmartTubeHarnessIdentity.isHarness(explicit)) {
                return SmartTubePinDecision.Rejected("harness_package_forbidden")
            }
            if (ForbiddenYouTubePackages.isYouTube(explicit)) {
                return SmartTubePinDecision.Rejected("youtube_package_forbidden")
            }
            if (!SmartTubePackageNames.isSafeSmartTubeTarget(explicit)) {
                return SmartTubePinDecision.Rejected("selected_package_not_smarttube")
            }
            val match = selectable.find { it.packageName == explicit }
                ?: return SmartTubePinDecision.Rejected("selected_package_not_device_installed")
            return SmartTubePinDecision.Selected(match.packageName)
        }
        return when (selectable.size) {
            0 -> SmartTubePinDecision.None
            1 -> SmartTubePinDecision.Selected(selectable[0].packageName)
            else -> SmartTubePinDecision.Ambiguous(selectable.map { it.packageName })
        }
    }
}
