package org.stypox.dicio.ui.driving

/**
 * STREAM_MUSIC in-app volume controller. Does not take microphone ownership and does not
 * leave DrivingScreen. Android STREAM_MUSIC is 3; ADJUST_LOWER/RAISE are -1/+1.
 */
object DrivingVolumePolicy {
    const val STREAM_MUSIC = 3
    const val ADJUST_LOWER = -1
    const val ADJUST_RAISE = 1
    const val AUTO_DISMISS_IDLE_MS = 4_000L
    const val MIN_TOUCH_TARGET_DP = 80

    fun quieter(current: Int, min: Int = 0): Int = (current - 1).coerceAtLeast(min)

    fun louder(current: Int, max: Int): Int = (current + 1).coerceAtLeast(0).coerceAtMost(max)

    fun toggleMuted(currentlyMuted: Boolean): Boolean = !currentlyMuted

    fun displayVolume(current: Int, max: Int, muted: Boolean): String {
        if (muted || current <= 0) return "0"
        return current.coerceIn(0, max).toString()
    }
}

sealed class VolumeOpResult {
    data class Ok(val volume: Int, val muted: Boolean) : VolumeOpResult()
    data object Failed : VolumeOpResult()
}

class DrivingVolumeController(
    private val getVolume: () -> Int,
    private val getMax: () -> Int,
    private val isMuted: () -> Boolean,
    private val setVolume: (Int) -> Unit,
    private val setMuted: (Boolean) -> Unit,
) {
    fun current(): VolumeOpResult = runCatching {
        VolumeOpResult.Ok(getVolume(), isMuted())
    }.getOrElse { VolumeOpResult.Failed }

    fun quieter(): VolumeOpResult = runCatching {
        val next = DrivingVolumePolicy.quieter(getVolume())
        setMuted(false)
        setVolume(next)
        VolumeOpResult.Ok(getVolume(), isMuted())
    }.getOrElse { VolumeOpResult.Failed }

    fun louder(): VolumeOpResult = runCatching {
        val next = DrivingVolumePolicy.louder(getVolume(), getMax())
        setMuted(false)
        setVolume(next)
        VolumeOpResult.Ok(getVolume(), isMuted())
    }.getOrElse { VolumeOpResult.Failed }

    fun toggleMute(): VolumeOpResult = runCatching {
        val muted = DrivingVolumePolicy.toggleMuted(isMuted() || getVolume() <= 0)
        setMuted(muted)
        VolumeOpResult.Ok(getVolume(), muted || getVolume() <= 0)
    }.getOrElse { VolumeOpResult.Failed }
}
