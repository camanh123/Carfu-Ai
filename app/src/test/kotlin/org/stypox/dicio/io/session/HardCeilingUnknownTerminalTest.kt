package org.stypox.dicio.io.session

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * SR_HARD_CEILING + UNKNOWN / incomplete rescue must terminate immediately.
 * Device-proven hang: locked UNKNOWN was treated as a successful rescue.
 */
class HardCeilingUnknownTerminalTest : StringSpec({
    val clock = object {
        var now: Long = 1_000_000L
    }

    beforeTest {
        VoiceTriggerManager.resetForTests()
        VoiceSessionManager.resetForTests()
        VoiceOnlinePolicy.resetForTests()
        CarfuSessionGate.resetForTests()
        CarfuActivationSource.resetForTests()
        clock.now = 1_000_000L
        VoiceTriggerManager.nowMs = { clock.now }
        VoiceSessionManager.nowMs = { clock.now }
        CarfuSessionGate.nowMs = { clock.now }
        CarfuSessionGate.setBackgroundWakeEnabled(true)
        VoiceOnlinePolicy.onlineOverride = true
        CarfuDiag.clear()
    }

    fun startSession(sessionId: Long) {
        VoiceTriggerManager.clearHardwareDebounce()
        val trigger = VoiceTriggerManager.request(VoiceTriggerManager.Origin.HARDWARE_MODE)
        trigger.accepted.shouldBeTrue()
        VoiceSessionManager.createFromTrigger(trigger.trigger!!, sessionId).shouldBe(
            VoiceSessionManager.liveSession(),
        )
        SessionCommandDecision.bindSession(sessionId)
        CanonicalActionGate.bind(sessionId)
        CommandSessionOutcome.bind(sessionId)
        StableCompletePartialTracker.bind(sessionId)
        VoiceSessionManager.requestListen(sessionId).shouldBeTrue()
        VoiceSessionManager.hasLiveSession().shouldBeTrue()
    }

    "đưa → đưa tôi is not an executable canonical command" {
        val dua = VietnameseCommandUnderstanding.understand("đưa", 40L)
        val duaToi = VietnameseCommandUnderstanding.understand("đưa tôi", 40L)
        HardCeilingRescuePolicy.isExecutableCanonical(dua).shouldBeFalse()
        HardCeilingRescuePolicy.isExecutableCanonical(duaToi).shouldBeFalse()
        duaToi.command.shouldBeNull()
        (duaToi.intent == VoiceIntent.UNKNOWN ||
            duaToi.completeness != SemanticCompleteness.COMPLETE).shouldBeTrue()
    }

    "locked UNKNOWN must not be treated as a successful rescue" {
        val unknown = UnderstandingResult.unknown(
            sessionId = 41L,
            raw = "đưa tôi",
            normalized = "dua toi",
            reason = "no_domain",
        )
        SessionCommandDecision.bindSession(41L)
        SessionCommandDecision.lockFinal(41L, unknown)
        HardCeilingRescuePolicy.shouldTreatLockedAsSuccessfulRescue(
            SessionCommandDecision.locked(41L),
        ).shouldBeFalse()
        HardCeilingRescuePolicy.shouldTerminateAfterRescue(
            SessionCommandDecision.locked(41L),
        ).shouldBeTrue()
    }

    "hard ceiling UNKNOWN terminates immediately with no live session" {
        val sessionId = 42L
        startSession(sessionId)
        val dua = VietnameseCommandUnderstanding.understand("đưa", sessionId)
        val duaToi = VietnameseCommandUnderstanding.understand("đưa tôi", sessionId)
        SessionCommandDecision.onPartial(sessionId, dua)
        SessionCommandDecision.onPartial(sessionId, duaToi)
        val rescued = SessionCommandDecision.decideFinal(
            sessionId,
            listOf("đưa" to 1f, "đưa tôi" to 1f),
        )
        HardCeilingRescuePolicy.isExecutableCanonical(rescued).shouldBeFalse()
        HardCeilingRescuePolicy.shouldTerminateAfterRescue(rescued).shouldBeTrue()
        HardCeilingRescuePolicy.shouldTreatLockedAsSuccessfulRescue(
            SessionCommandDecision.locked(sessionId),
        ).shouldBeFalse()

        CommandSessionOutcome.claim(
            CommandSessionOutcome.Kind.NO_SPEECH,
            sessionId,
        ).shouldBeTrue()
        VoiceSessionManager.terminate(sessionId, "hard_ceiling_unknown")
        HardCeilingRescuePolicy.log(
            sessionId = sessionId,
            rescue = rescued,
            terminalTriggered = true,
            terminalReason = "hard_ceiling_unknown",
            srDestroyed = true,
            audioFocusReleased = true,
        )

        VoiceSessionManager.hasLiveSession().shouldBeFalse()
        VoiceSessionManager.liveSession().shouldBeNull()
        VoiceSessionManager.shouldIgnoreCallback(sessionId).shouldBeTrue()
        CommandSessionOutcome.claimedForTests().shouldBeTrue()
        val line = CarfuDiag.recent(CarfuDiag.TAG_VOICE).last { it.contains("SR_HARD_CEILING") }
        line shouldContain "SESSION_ID=42"
        line shouldContain "RESCUE_RESULT="
        line shouldContain "CANONICAL_COMMAND_PRESENT=false"
        line shouldContain "TERMINAL_TRIGGERED=true"
        line shouldContain "TERMINAL_REASON=hard_ceiling_unknown"
        line shouldContain "SR_DESTROYED=true"
        line shouldContain "AUDIO_FOCUS_RELEASED=true"

        VoiceTriggerManager.clearHardwareDebounce()
        val nextTrigger = VoiceTriggerManager.request(VoiceTriggerManager.Origin.HARDWARE_MODE)
        nextTrigger.accepted.shouldBeTrue()
        val nextSession = VoiceSessionManager.createFromTrigger(nextTrigger.trigger!!, 43L)
        nextSession.shouldNotBeNull()
        nextSession!!.sessionId shouldBe 43L
        VoiceSessionManager.shouldIgnoreCallback(sessionId).shouldBeTrue()
    }
})
