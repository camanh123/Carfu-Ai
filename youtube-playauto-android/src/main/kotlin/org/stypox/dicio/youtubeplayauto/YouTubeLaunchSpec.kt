package org.stypox.dicio.youtubeplayauto

import org.stypox.dicio.playauto.core.PlaybackStrategy

/** Platform-agnostic launch description. No Android types. */
data class YouTubeLaunchSpec(
    val strategy: PlaybackStrategy,
    val action: String,
    val packageName: String?,
    val uri: String? = null,
    val extraQuery: String? = null,
    val categories: Set<String> = emptySet(),
) {
    companion object {
        const val ACTION_VIEW = "android.intent.action.VIEW"
        const val ACTION_MAIN = "android.intent.action.MAIN"
        const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"
    }
}
