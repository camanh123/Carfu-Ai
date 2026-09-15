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

    val CATALOG_PACKAGES: List<String> = listOf(
        TEAMSMART,
        LISKOVSOFT_SMARTTUBE,
        LISKOVSOFT_SMARTYOUTUBE,
    )

    const val PACKAGE_EVIDENCE = "catalog_only_not_device_proven"
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
        val n = packageName.trim().lowercase()
        if (n.isEmpty()) return false
        if (n in SmartTubeCatalog.CATALOG_PACKAGES) return true
        return n.contains("smarttube") || n.contains("smartyoutubetv")
    }

    fun isSafeSmartTubeTarget(packageName: String?): Boolean {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty()) return false
        if (ForbiddenYouTubePackages.isYouTube(pkg)) return false
        return looksLikeSmartTube(pkg)
    }
}
