package org.stypox.dicio.io.wake

import org.stypox.dicio.io.session.CommandSessionPhase
import org.stypox.dicio.settings.datastore.BackgroundWake
import org.stypox.dicio.settings.datastore.UserSettings

/**
 * JVM-testable decisions for CARFU background wake.
 *
 * CARFU default: background wake is **on** for [BackgroundWake.BACKGROUND_WAKE_UNSET] and
 * [BackgroundWake.BACKGROUND_WAKE_ENABLED]. This fork is a head-unit always-listening assistant.
 * Do not copy this default to unrelated Dicio builds.
 *
 * MainActivity may start or observe the foreground WakeService, but it must not own the
 * service lifetime or the wake AudioRecord.
 */
object BackgroundWakePolicy {
    const val INITIAL_OPEN_RETRY_MS = 250L
    const val MAX_OPEN_RETRY_MS = 15_000L
    const val STALE_READ_MS = 2_000L

    /**
     * True unless the user explicitly opted out. UNSET stays on so existing installs do not
     * silently lose background wake after this field is added.
     */
    fun isBackgroundWakeEnabled(settings: UserSettings): Boolean =
        isBackgroundWakeEnabled(settings.backgroundWake)

    fun isBackgroundWakeEnabled(value: BackgroundWake): Boolean =
        value != BackgroundWake.BACKGROUND_WAKE_DISABLED

    fun activityOnStopShouldStopWakeService(): Boolean = false

    fun activityOnDestroyOwnsWakeAudioRecord(): Boolean = false

    fun activityOnDestroyShouldStopWakeService(): Boolean = false

    fun shouldStartWakeService(
        backgroundWakeEnabled: Boolean,
        recordAudioGranted: Boolean,
        wakeDeviceEnabled: Boolean,
        wakeModelReadyOrPending: Boolean,
    ): Boolean = backgroundWakeEnabled &&
        recordAudioGranted &&
        wakeDeviceEnabled &&
        wakeModelReadyOrPending

    fun shouldStartOnBoot(
        backgroundWakeEnabled: Boolean,
        recordAudioGranted: Boolean,
        wakeDeviceEnabled: Boolean,
        wakeModelReadyOrPending: Boolean,
    ): Boolean = shouldStartWakeService(
        backgroundWakeEnabled = backgroundWakeEnabled,
        recordAudioGranted = recordAudioGranted,
        wakeDeviceEnabled = wakeDeviceEnabled,
        wakeModelReadyOrPending = wakeModelReadyOrPending,
    )

    fun isBootAction(action: String?): Boolean = when (action) {
        "android.intent.action.BOOT_COMPLETED",
        "android.intent.action.LOCKED_BOOT_COMPLETED",
        "android.intent.action.QUICKBOOT_POWERON",
        "com.htc.intent.action.QUICKBOOT_POWERON" -> true
        else -> false
    }

    fun isWakeModelReadyOrPending(state: WakeState?): Boolean = when (state) {
        WakeState.NotLoaded, WakeState.Loading, WakeState.Loaded -> true
        else -> false
    }

    /** Android delivers one Service instance; a second start must not open another recorder. */
    fun skipDuplicateListenLoop(alreadyListening: Boolean): Boolean = alreadyListening

    fun serviceStartIsIdempotent(): Boolean = true

    /**
     * A new process after START_STICKY has a fresh CommandSession at IDLE_WAKE.
     * A duplicate onStartCommand in the same process must not reset an in-flight session.
     */
    fun phaseAfterServiceStart(
        alreadyListening: Boolean,
        currentPhase: CommandSessionPhase,
    ): CommandSessionPhase =
        if (alreadyListening) currentPhase else CommandSessionPhase.IDLE_WAKE

    enum class ScreenAction {
        OFF,
        ON,
        USER_PRESENT,
    }

    data class CaptureRepairDecision(
        val keepServiceAlive: Boolean,
        val releaseCurrentRecord: Boolean,
        val openReplacementRecord: Boolean,
    ) {
        val wouldDuplicateCapture: Boolean
            get() = openReplacementRecord && !releaseCurrentRecord
    }

    /**
     * Android 10 microphone foreground services may keep recording while the screen is off.
     * Screen-on repairs a *lost* capture in the existing listen loop; it never starts a second
     * AudioRecord beside a healthy one, and it never steals the mic during command STT.
     */
    fun onScreenEvent(
        action: ScreenAction,
        listening: Boolean,
        commandSessionBusy: Boolean,
        recording: Boolean,
        lastSuccessfulReadAgeMs: Long,
        lastReadFailed: Boolean,
    ): CaptureRepairDecision {
        if (!listening) {
            return CaptureRepairDecision(
                keepServiceAlive = false,
                releaseCurrentRecord = recording,
                openReplacementRecord = false,
            )
        }
        if (commandSessionBusy) {
            return CaptureRepairDecision(
                keepServiceAlive = true,
                releaseCurrentRecord = false,
                openReplacementRecord = false,
            )
        }
        if (action == ScreenAction.OFF) {
            return CaptureRepairDecision(
                keepServiceAlive = true,
                releaseCurrentRecord = false,
                openReplacementRecord = false,
            )
        }
        val lost = lastReadFailed || !recording ||
            (recording && lastSuccessfulReadAgeMs > STALE_READ_MS)
        if (!lost) {
            return CaptureRepairDecision(
                keepServiceAlive = true,
                releaseCurrentRecord = false,
                openReplacementRecord = false,
            )
        }
        return CaptureRepairDecision(
            keepServiceAlive = true,
            releaseCurrentRecord = recording,
            openReplacementRecord = true,
        )
    }

    fun shouldRecreateAfterReadError(readResult: Int): Boolean = readResult < 0

    fun nextOpenRetryDelayMs(previousDelayMs: Long): Long {
        val base = previousDelayMs.coerceAtLeast(INITIAL_OPEN_RETRY_MS)
        return (base * 2).coerceAtMost(MAX_OPEN_RETRY_MS)
    }

    data class UserStopResult(
        val persistDisabled: Boolean = true,
        val releaseAudioRecord: Boolean = true,
        val abandonAudioFocus: Boolean = true,
        val preventBootStart: Boolean = true,
        val stopService: Boolean = true,
    )

    fun userDisableBackgroundWake(): UserStopResult = UserStopResult()

    fun wakeCommandCycleRequiresActivity(): Boolean = false

    fun commandCapturePathsAreMutuallyExclusive(
        directRunning: Boolean,
        fallbackRunning: Boolean,
    ): Boolean = !(directRunning && fallbackRunning)

    fun notificationKind(
        recordAudioGranted: Boolean,
        backgroundWakeEnabled: Boolean,
        wakeDeviceEnabled: Boolean,
        phase: CommandSessionPhase,
    ): WakeNotificationKind {
        if (!recordAudioGranted) return WakeNotificationKind.NEED_PERMISSION
        if (!backgroundWakeEnabled || !wakeDeviceEnabled) return WakeNotificationKind.MIC_OFF
        return when (phase) {
            CommandSessionPhase.IDLE_WAKE,
            CommandSessionPhase.RETURNING_TO_WAKE -> WakeNotificationKind.WAITING_WAKE
            CommandSessionPhase.COMMAND_LISTENING -> WakeNotificationKind.LISTENING_COMMAND
            CommandSessionPhase.WAKE_DETECTED,
            CommandSessionPhase.ACKNOWLEDGING,
            CommandSessionPhase.PROCESSING,
            CommandSessionPhase.RESPONDING -> WakeNotificationKind.PROCESSING
        }
    }

}

enum class WakeNotificationKind {
    WAITING_WAKE,
    LISTENING_COMMAND,
    PROCESSING,
    MIC_OFF,
    NEED_PERMISSION,
}
