package org.stypox.dicio.youtubeplayauto

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager

class AndroidYouTubeInAppSelector(
    private val context: Context,
) : YouTubeInAppSelector {
    override fun isAvailable(): Boolean {
        if (YouTubePlayAutoAccessibilityService.instance != null) return true
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        if (!am.isEnabled) return false
        val needle = context.packageName + "/" +
            YouTubePlayAutoAccessibilityService::class.java.name
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                val id = info.id.orEmpty()
                id.contains("YouTubePlayAutoAccessibilityService") || id == needle
            }
    }

    override fun selectAndPlay(
        query: String,
        youtubePackage: String,
        mode: YouTubeLaunchMode,
    ): YouTubeSelectOutcome {
        if (mode == YouTubeLaunchMode.DRY_RUN) {
            return YouTubeSelectOutcome.DryRun(query = query, wouldSeek = isAvailable())
        }
        if (!isAvailable()) {
            return YouTubeSelectOutcome.Unavailable(
                "enable_harness_accessibility_service_to_select_inside_youtube",
            )
        }
        YouTubePlayAutoSelectBus.arm(query, youtubePackage)
        return YouTubeSelectOutcome.Armed(query, youtubePackage)
    }
}
