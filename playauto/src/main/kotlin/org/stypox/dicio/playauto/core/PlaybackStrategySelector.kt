package org.stypox.dicio.playauto.core

/**
 * Strongest-first capability selection. Independent of Android.
 */
object PlaybackStrategySelector {
    val PRIORITY: List<PlaybackCapability> = listOf(
        PlaybackCapability.DIRECT_PLAY,
        PlaybackCapability.DEEP_LINK,
        PlaybackCapability.SEARCH,
        PlaybackCapability.OPEN_APP,
    )

    fun select(capabilities: Set<PlaybackCapability>): PlaybackStrategy? {
        val chosen = PRIORITY.firstOrNull { it in capabilities } ?: return null
        return chosen.toStrategy()
    }
}
