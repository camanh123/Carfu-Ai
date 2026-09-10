package org.stypox.dicio.youtubeplayauto

/**
 * Candidate YouTube package ids observed in CARFU catalogs.
 * Presence is verified at runtime by [YouTubeRuntime], never assumed.
 */
object YouTubeCandidatePackages {
    const val OFFICIAL = "com.google.android.youtube"
    const val OFFICIAL_TV = "com.google.android.youtube.tv"
    const val VANCED = "com.vanced.android.youtube"
    const val REVANCED = "app.revanced.android.youtube"

    val discoveryOrder: List<String> = listOf(
        OFFICIAL,
        OFFICIAL_TV,
        VANCED,
        REVANCED,
    )
}
