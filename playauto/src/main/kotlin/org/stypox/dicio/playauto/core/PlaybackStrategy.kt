package org.stypox.dicio.playauto.core

enum class PlaybackStrategy {
    DIRECT_PLAY,
    DEEP_LINK,
    SEARCH,
    OPEN_APP,
}

fun PlaybackCapability.toStrategy(): PlaybackStrategy = when (this) {
    PlaybackCapability.DIRECT_PLAY -> PlaybackStrategy.DIRECT_PLAY
    PlaybackCapability.DEEP_LINK -> PlaybackStrategy.DEEP_LINK
    PlaybackCapability.SEARCH -> PlaybackStrategy.SEARCH
    PlaybackCapability.OPEN_APP -> PlaybackStrategy.OPEN_APP
}
