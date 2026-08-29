package org.stypox.dicio.io.session

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Distinguishes automatic “CARFU ơi” sessions from a manual microphone tap.
 * Automatic empty/false wakes return silently with a cooldown; a manual tap
 * may speak “Tôi chưa nghe rõ” once.
 */
object CarfuActivationSource {
    enum class Kind { AUTOMATIC_WAKE, MANUAL_MIC }

    @Volatile
    var kind: Kind = Kind.AUTOMATIC_WAKE
        private set

    private val manualUnclearConsumed = AtomicBoolean(false)

    fun markAutomaticWake() {
        kind = Kind.AUTOMATIC_WAKE
        manualUnclearConsumed.set(false)
    }

    fun markManualMic() {
        kind = Kind.MANUAL_MIC
        manualUnclearConsumed.set(false)
    }

    fun isManual(): Boolean = kind == Kind.MANUAL_MIC

    fun shouldSpeakUnclear(): Boolean {
        if (kind != Kind.MANUAL_MIC) return false
        return manualUnclearConsumed.compareAndSet(false, true)
    }

    fun shouldApplyFalseWakeCooldown(): Boolean = kind == Kind.AUTOMATIC_WAKE

    fun resetForTests() {
        kind = Kind.AUTOMATIC_WAKE
        manualUnclearConsumed.set(false)
    }
}
