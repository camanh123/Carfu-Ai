package org.stypox.dicio.io.wake

import org.stypox.dicio.io.session.CommandSessionPhase

/**
 * Single-shot wake gate for CARFU. OpenWakeWord scores are in roughly `[0, 1]`.
 *
 * Old production threshold was [OLD_CARFU_WAKE_THRESHOLD] (single noisy frame).
 * New conservative threshold is [CARFU_WAKE_THRESHOLD] plus [CONSECUTIVE_HITS_REQUIRED]
 * consecutive frames, plus recorder warmup and a post-assistant-TTS cooldown.
 */
class WakeAcceptancePolicy(
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    enum class Verdict {
        DISCARD_PAUSED,
        DISCARD_WARMUP,
        DISCARD_COOLDOWN,
        DISCARD_STALE_AFTER_REPAIR,
        REJECT_NOT_IDLE,
        REJECT_TTS,
        REJECT_COMMAND,
        REJECT_RESPONSE,
        REJECT_NO_VAD,
        BELOW_THRESHOLD,
        ACCUMULATING,
        ACCEPT,
    }

    var consecutiveHits: Int = 0
        private set
    var paused: Boolean = false
        private set
    var gateOpen: Boolean = true
        private set
    var sessionRefractory: Boolean = false
        private set

    private var warmupUntilMs: Long = 0L
    private var cooldownUntilMs: Long = 0L
    private var recorderGeneration: Int = 0
    private var lastProcessedGeneration: Int = -1

    fun onRecorderStarted() {
        recorderGeneration += 1
        warmupUntilMs = clockMs() + RECORDER_WARMUP_MS
        consecutiveHits = 0
        paused = false
        lastProcessedGeneration = -1
        if (!isCooldownActive()) {
            sessionRefractory = false
            gateOpen = true
        }
    }

    fun onRepairRecreatedRecorder() {
        onRecorderStarted()
    }

    fun onPauseForInteraction() {
        paused = true
        gateOpen = false
        sessionRefractory = true
        consecutiveHits = 0
    }

    fun onDetectorAndPcmReset() {
        consecutiveHits = 0
    }

    fun markPostAssistantTtsCooldown() {
        cooldownUntilMs = clockMs() + POST_ASSISTANT_TTS_WAKE_COOLDOWN_MS
        consecutiveHits = 0
        sessionRefractory = true
        gateOpen = false
        paused = true
    }

    fun markAutomaticFalseWakeCooldown() {
        cooldownUntilMs = clockMs() + AUTOMATIC_FALSE_WAKE_COOLDOWN_MS
        consecutiveHits = 0
        sessionRefractory = true
        gateOpen = false
        paused = true
    }

    fun onCooldownElapsed() {
        if (clockMs() >= cooldownUntilMs) {
            sessionRefractory = false
            gateOpen = true
            paused = false
            consecutiveHits = 0
        }
    }

    fun isCooldownActive(): Boolean = clockMs() < cooldownUntilMs

    fun isWarmupActive(): Boolean = clockMs() < warmupUntilMs

    fun remainingCooldownMs(): Long = (cooldownUntilMs - clockMs()).coerceAtLeast(0L)

    /**
     * Do not open a *second* AudioRecord. The physical hub recorder stays open for the
     * full wake-command-response lifecycle; this only blocks replacement/duplicate opens.
     */
    fun shouldHoldWakeRecorderClosed(
        commandSessionBusy: Boolean,
    ): Boolean = paused || sessionRefractory || isCooldownActive() || commandSessionBusy

    fun mayOpenReplacementRecorder(
        commandSessionBusy: Boolean,
        alreadyRecordingHealthy: Boolean,
    ): Boolean {
        if (shouldHoldWakeRecorderClosed(commandSessionBusy)) return false
        if (alreadyRecordingHealthy) return false
        return true
    }

    /**
     * @param frameRecorderGeneration frames captured before the current recorder start must not
     * score. Pass the generation from before [onRecorderStarted] to simulate stale buffers.
     */
    fun evaluate(
        scoreAboveThreshold: Boolean,
        phase: CommandSessionPhase,
        ttsSpeaking: Boolean = false,
        commandCaptureActive: Boolean = false,
        frameRecorderGeneration: Int? = recorderGeneration,
        voiceActivity: Boolean = true,
    ): Verdict {
        if (isCooldownActive()) {
            consecutiveHits = 0
            return Verdict.DISCARD_COOLDOWN
        }
        if (paused || sessionRefractory) {
            consecutiveHits = 0
            return Verdict.DISCARD_PAUSED
        }
        if (isWarmupActive()) {
            consecutiveHits = 0
            return Verdict.DISCARD_WARMUP
        }
        val frameGen = frameRecorderGeneration ?: recorderGeneration
        if (frameGen != recorderGeneration) {
            consecutiveHits = 0
            return Verdict.DISCARD_STALE_AFTER_REPAIR
        }
        lastProcessedGeneration = frameGen

        if (ttsSpeaking || phase == CommandSessionPhase.ACKNOWLEDGING) {
            consecutiveHits = 0
            return Verdict.REJECT_TTS
        }
        if (commandCaptureActive || phase == CommandSessionPhase.COMMAND_LISTENING) {
            consecutiveHits = 0
            return Verdict.REJECT_COMMAND
        }
        if (phase == CommandSessionPhase.PROCESSING ||
            phase == CommandSessionPhase.RESPONDING ||
            phase == CommandSessionPhase.RETURNING_TO_WAKE ||
            phase == CommandSessionPhase.WAKE_DETECTED
        ) {
            consecutiveHits = 0
            return if (phase == CommandSessionPhase.WAKE_DETECTED) {
                Verdict.REJECT_NOT_IDLE
            } else {
                Verdict.REJECT_RESPONSE
            }
        }
        if (phase != CommandSessionPhase.IDLE_WAKE || !gateOpen) {
            consecutiveHits = 0
            return Verdict.REJECT_NOT_IDLE
        }
        if (!voiceActivity) {
            consecutiveHits = 0
            return Verdict.REJECT_NO_VAD
        }
        if (!scoreAboveThreshold) {
            consecutiveHits = 0
            return Verdict.BELOW_THRESHOLD
        }
        consecutiveHits += 1
        if (consecutiveHits < CONSECUTIVE_HITS_REQUIRED) {
            return Verdict.ACCUMULATING
        }
        consecutiveHits = 0
        return Verdict.ACCEPT
    }

    fun closeGate() {
        gateOpen = false
        sessionRefractory = true
        paused = true
        consecutiveHits = 0
    }

    fun currentRecorderGeneration(): Int = recorderGeneration

    companion object {
        const val OLD_CARFU_WAKE_THRESHOLD = 0.65f
        const val CARFU_WAKE_THRESHOLD = 0.82f
        const val CUSTOM_WAKE_THRESHOLD = 0.8f
        const val SCORE_RANGE_MIN = 0.0f
        const val SCORE_RANGE_MAX = 1.0f
        const val CONSECUTIVE_HITS_REQUIRED = 3
        const val RECORDER_WARMUP_MS = 500L
        const val POST_ASSISTANT_TTS_WAKE_COOLDOWN_MS = 5_000L
        const val AUTOMATIC_FALSE_WAKE_COOLDOWN_MS = 10_000L

        /** The one physical recorder is never released just to hand the mic to command STT. */
        fun shouldReleasePhysicalRecorderForSession(): Boolean = false

        fun wakeAndCommandMicsOverlap(
            wakeRecorderHeld: Boolean,
            commandRecorderHeld: Boolean,
        ): Boolean = wakeRecorderHeld && commandRecorderHeld

        fun commandSttMayStart(
            ttsCompleted: Boolean,
            alreadyStarted: Boolean,
            sharedHubRecording: Boolean = true,
        ): Boolean = ttsCompleted && !alreadyStarted && sharedHubRecording
    }
}
