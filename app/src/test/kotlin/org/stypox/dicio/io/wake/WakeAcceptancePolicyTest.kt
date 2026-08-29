package org.stypox.dicio.io.wake

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.stypox.dicio.io.session.CommandSessionMachine
import org.stypox.dicio.io.session.CommandSessionPhase

class WakeAcceptancePolicyTest : StringSpec({
    "old and new CARFU thresholds and OpenWakeWord score range are documented" {
        WakeAcceptancePolicy.OLD_CARFU_WAKE_THRESHOLD shouldBe 0.65f
        WakeAcceptancePolicy.CARFU_WAKE_THRESHOLD shouldBe 0.82f
        WakeAcceptancePolicy.CARFU_WAKE_THRESHOLD.shouldBeGreaterThan(
            WakeAcceptancePolicy.OLD_CARFU_WAKE_THRESHOLD,
        )
        WakeAcceptancePolicy.SCORE_RANGE_MAX.shouldBeGreaterThan(
            WakeAcceptancePolicy.SCORE_RANGE_MIN,
        )
        WakeAcceptancePolicy.CONSECUTIVE_HITS_REQUIRED.shouldBeGreaterThan(1)
    }

    "1 only IDLE_WAKE accepts a wake candidate" {
        val clock = mutableListOf(1_000L)
        val policy = primedPolicy(clock)
        policy.evaluate(true, CommandSessionPhase.WAKE_DETECTED) shouldBe
            WakeAcceptancePolicy.Verdict.REJECT_NOT_IDLE
        acceptWake(policy) shouldBe WakeAcceptancePolicy.Verdict.ACCEPT
    }

    "2 duplicate wake callbacks create one session" {
        val machine = CommandSessionMachine()
        val clock = mutableListOf(1_000L)
        val policy = primedPolicy(clock)
        acceptWake(policy) shouldBe WakeAcceptancePolicy.Verdict.ACCEPT
        machine.onWakeDetected().shouldBeTrue()
        policy.closeGate()
        acceptWake(policy) shouldBe WakeAcceptancePolicy.Verdict.DISCARD_PAUSED
        machine.onWakeDetected().shouldBeFalse()
        machine.sessionId shouldBe 1L
    }

    "3 wake is rejected while TTS speaks" {
        val policy = primedPolicy(mutableListOf(1_000L))
        policy.evaluate(
            scoreAboveThreshold = true,
            phase = CommandSessionPhase.ACKNOWLEDGING,
            ttsSpeaking = true,
        ) shouldBe WakeAcceptancePolicy.Verdict.REJECT_TTS
    }

    "4 wake is rejected during command capture" {
        val policy = primedPolicy(mutableListOf(1_000L))
        policy.evaluate(
            scoreAboveThreshold = true,
            phase = CommandSessionPhase.COMMAND_LISTENING,
            commandCaptureActive = true,
        ) shouldBe WakeAcceptancePolicy.Verdict.REJECT_COMMAND
    }

    "5 wake is rejected during response" {
        val policy = primedPolicy(mutableListOf(1_000L))
        policy.evaluate(true, CommandSessionPhase.RESPONDING) shouldBe
            WakeAcceptancePolicy.Verdict.REJECT_RESPONSE
        policy.evaluate(true, CommandSessionPhase.PROCESSING) shouldBe
            WakeAcceptancePolicy.Verdict.REJECT_RESPONSE
    }

    "6 detector and PCM reset on pause/resume" {
        val clock = mutableListOf(1_000L)
        val policy = primedPolicy(clock)
        policy.evaluate(true, CommandSessionPhase.IDLE_WAKE)
        policy.consecutiveHits shouldBe 1
        policy.onPauseForInteraction()
        policy.onDetectorAndPcmReset()
        policy.consecutiveHits shouldBe 0
        clock[0] = 4_000L
        policy.markPostAssistantTtsCooldown()
        clock[0] = 4_000L + WakeAcceptancePolicy.POST_ASSISTANT_TTS_WAKE_COOLDOWN_MS
        policy.onCooldownElapsed()
        policy.onRecorderStarted()
        policy.onDetectorAndPcmReset()
        policy.consecutiveHits shouldBe 0
        policy.paused.shouldBeFalse()
        policy.gateOpen.shouldBeTrue()
    }

    "7 stale frames after repair cannot trigger" {
        val clock = mutableListOf(1_000L)
        val policy = primedPolicy(clock)
        val oldGeneration = policy.currentRecorderGeneration()
        policy.onRepairRecreatedRecorder()
        clock[0] = 2_000L
        policy.evaluate(
            scoreAboveThreshold = true,
            phase = CommandSessionPhase.IDLE_WAKE,
            frameRecorderGeneration = oldGeneration,
        ) shouldBe WakeAcceptancePolicy.Verdict.DISCARD_STALE_AFTER_REPAIR
    }

    "8 post-TTS cooldown prevents self-trigger" {
        val clock = mutableListOf(1_000L)
        val policy = primedPolicy(clock)
        policy.markPostAssistantTtsCooldown()
        clock[0] += WakeAcceptancePolicy.POST_ASSISTANT_TTS_WAKE_COOLDOWN_MS - 1
        policy.onCooldownElapsed()
        policy.evaluate(true, CommandSessionPhase.IDLE_WAKE) shouldBe
            WakeAcceptancePolicy.Verdict.DISCARD_COOLDOWN
    }

    "9 valid intentional wake works after cooldown" {
        val clock = mutableListOf(1_000L)
        val policy = primedPolicy(clock)
        policy.markPostAssistantTtsCooldown()
        clock[0] += WakeAcceptancePolicy.POST_ASSISTANT_TTS_WAKE_COOLDOWN_MS
        policy.onCooldownElapsed()
        policy.onRecorderStarted()
        clock[0] += WakeAcceptancePolicy.RECORDER_WARMUP_MS
        policy.onDetectorAndPcmReset()
        acceptWake(policy) shouldBe WakeAcceptancePolicy.Verdict.ACCEPT
    }

    "warmup discards frames for 500 ms" {
        val clock = mutableListOf(5_000L)
        val policy = WakeAcceptancePolicy { clock[0] }
        policy.onRecorderStarted()
        policy.evaluate(true, CommandSessionPhase.IDLE_WAKE) shouldBe
            WakeAcceptancePolicy.Verdict.DISCARD_WARMUP
        clock[0] += WakeAcceptancePolicy.RECORDER_WARMUP_MS
        policy.evaluate(false, CommandSessionPhase.IDLE_WAKE) shouldBe
            WakeAcceptancePolicy.Verdict.BELOW_THRESHOLD
    }

    "single noisy frame is not enough" {
        val policy = primedPolicy(mutableListOf(1_000L))
        policy.evaluate(true, CommandSessionPhase.IDLE_WAKE) shouldBe
            WakeAcceptancePolicy.Verdict.ACCUMULATING
        policy.evaluate(false, CommandSessionPhase.IDLE_WAKE) shouldBe
            WakeAcceptancePolicy.Verdict.BELOW_THRESHOLD
        policy.consecutiveHits shouldBe 0
    }

    "repair must not open a second recorder while a session is busy" {
        val policy = primedPolicy(mutableListOf(1_000L))
        policy.closeGate()
        policy.mayOpenReplacementRecorder(
            commandSessionBusy = true,
            alreadyRecordingHealthy = false,
        ).shouldBeFalse()
        policy.shouldHoldWakeRecorderClosed(commandSessionBusy = true).shouldBeTrue()
    }

    "wake and command microphones cannot overlap" {
        WakeAcceptancePolicy.wakeAndCommandMicsOverlap(true, true).shouldBeTrue()
        WakeAcceptancePolicy.wakeAndCommandMicsOverlap(true, false).shouldBeFalse()
        WakeAcceptancePolicy.shouldReleasePhysicalRecorderForSession().shouldBeFalse()
        WakeAcceptancePolicy.commandSttMayStart(
            ttsCompleted = true,
            alreadyStarted = false,
            sharedHubRecording = true,
        ).shouldBeTrue()
        WakeAcceptancePolicy.commandSttMayStart(
            ttsCompleted = true,
            alreadyStarted = false,
            sharedHubRecording = false,
        ).shouldBeFalse()
        WakeAcceptancePolicy.commandSttMayStart(
            ttsCompleted = true,
            alreadyStarted = true,
            sharedHubRecording = true,
        ).shouldBeFalse()
    }

    "score without voice activity cannot accept" {
        val policy = primedPolicy(mutableListOf(1_000L))
        repeat(WakeAcceptancePolicy.CONSECUTIVE_HITS_REQUIRED) {
            policy.evaluate(
                scoreAboveThreshold = true,
                phase = CommandSessionPhase.IDLE_WAKE,
                voiceActivity = false,
            ) shouldBe WakeAcceptancePolicy.Verdict.REJECT_NO_VAD
        }
        policy.consecutiveHits shouldBe 0
    }

    "post-assistant TTS cooldown lasts five seconds" {
        WakeAcceptancePolicy.POST_ASSISTANT_TTS_WAKE_COOLDOWN_MS shouldBe 5_000L
    }

    "automatic false-wake cooldown lasts 10 seconds" {
        val clock = mutableListOf(1_000L)
        val policy = primedPolicy(clock)
        policy.markAutomaticFalseWakeCooldown()
        clock[0] += WakeAcceptancePolicy.AUTOMATIC_FALSE_WAKE_COOLDOWN_MS - 1
        policy.onCooldownElapsed()
        policy.evaluate(true, CommandSessionPhase.IDLE_WAKE) shouldBe
            WakeAcceptancePolicy.Verdict.DISCARD_COOLDOWN
        clock[0] += 1
        policy.onCooldownElapsed()
        policy.onRecorderStarted()
        clock[0] += WakeAcceptancePolicy.RECORDER_WARMUP_MS
        policy.onDetectorAndPcmReset()
        acceptWake(policy) shouldBe WakeAcceptancePolicy.Verdict.ACCEPT
    }
})

private fun primedPolicy(clock: MutableList<Long>): WakeAcceptancePolicy {
    val policy = WakeAcceptancePolicy { clock[0] }
    policy.onRecorderStarted()
    clock[0] += WakeAcceptancePolicy.RECORDER_WARMUP_MS
    policy.onDetectorAndPcmReset()
    return policy
}

private fun acceptWake(policy: WakeAcceptancePolicy): WakeAcceptancePolicy.Verdict {
    var last = WakeAcceptancePolicy.Verdict.BELOW_THRESHOLD
    repeat(WakeAcceptancePolicy.CONSECUTIVE_HITS_REQUIRED) {
        last = policy.evaluate(true, CommandSessionPhase.IDLE_WAKE)
    }
    return last
}
