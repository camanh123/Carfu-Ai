package org.stypox.dicio.io.session

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Distinguishes automatic “CARFU ơi” sessions, a manual microphone tap, and
 * the steering MODE / system-assistant button.
 *
 * Automatic empty/false wakes return silently with a cooldown. A manual tap
 * or MODE press may speak “Tôi chưa nghe rõ” once. MODE never uses the 10s
 * automatic false-wake cooldown.
 */
object CarfuActivationSource {
    enum class Kind { AUTOMATIC_WAKE, MANUAL_MIC, HARDWARE_BUTTON }

    @Volatile
    var kind: Kind = Kind.AUTOMATIC_WAKE
        private set

    private val userUnclearConsumed = AtomicBoolean(false)

    fun markAutomaticWake() {
        kind = Kind.AUTOMATIC_WAKE
        userUnclearConsumed.set(false)
    }

    fun markManualMic() {
        kind = Kind.MANUAL_MIC
        userUnclearConsumed.set(false)
    }

    fun markHardwareButton() {
        kind = Kind.HARDWARE_BUTTON
        userUnclearConsumed.set(false)
    }

    fun isManual(): Boolean = kind == Kind.MANUAL_MIC

    fun isUserInitiated(): Boolean =
        kind == Kind.MANUAL_MIC || kind == Kind.HARDWARE_BUTTON

    fun shouldSpeakUnclear(
        origin: Kind = kind,
    ): Boolean {
        if (origin != Kind.MANUAL_MIC && origin != Kind.HARDWARE_BUTTON) return false
        return userUnclearConsumed.compareAndSet(false, true)
    }

    fun shouldApplyFalseWakeCooldown(
        origin: Kind = kind,
    ): Boolean = origin == Kind.AUTOMATIC_WAKE

    fun resetForTests() {
        kind = Kind.AUTOMATIC_WAKE
        userUnclearConsumed.set(false)
    }
}
