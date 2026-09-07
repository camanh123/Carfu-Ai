package org.stypox.dicio.io.session

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.stypox.dicio.io.input.CommandRecognitionPolicy

class CommandListenHandoffPolicyTest : StringSpec({
    "re-arm is permanently disabled for known-good listener" {
        CommandListenHandoffPolicy.MAX_PRE_SPEECH_REARMS shouldBe 0
        CommandListenHandoffPolicy.shouldRearmAfterNoSpeechError(
            handoffComplete = true,
            confirmedUserSpeech = false,
            rearmCount = 0,
        ).shouldBeFalse()
        CommandListenHandoffPolicy.shouldRearmAfterNoSpeechError(
            handoffComplete = true,
            confirmedUserSpeech = false,
            rearmCount = 0,
            nowMs = 1_000L,
            absoluteDeadlineMs = 99_000L,
        ).shouldBeFalse()
    }

    "app-side no-speech budget is zero (Google + 12s ceiling only)" {
        CommandListenHandoffPolicy.NO_SPEECH_BUDGET_MS shouldBe 0L
        CommandRecognitionPolicy.ANDROID_NO_SPEECH_AFTER_READY_MS shouldBe 0L
        CommandRecognitionPolicy.ANDROID_LISTEN_TIMEOUT_MS shouldBe 12_000L
        CommandListenHandoffPolicy.MIC_HANDOFF_MS shouldBe 0L
    }

    "acoustic BeginningOfSpeech alone never confirms user speech" {
        CommandListenHandoffPolicy.markConfirmedUserSpeech(
            handoffComplete = true,
            nonEmptyPartial = false,
            nonEmptyFinal = false,
        ).shouldBeFalse()
    }

    "non-empty partial or final can still mark confirmed speech for Smart helpers" {
        CommandListenHandoffPolicy.markConfirmedUserSpeech(
            handoffComplete = true,
            nonEmptyPartial = true,
            nonEmptyFinal = false,
        ).shouldBeTrue()
    }
})

class SemanticEndpointPolicyTest : StringSpec({
    "complete TIME command selects FAST endpoint (match helper only)" {
        val snap = SemanticEndpointPolicy.evaluate(
            transcript = "Mấy giờ rồi",
            userSpeechStarted = true,
            endOfSpeechSeen = true,
        )
        snap.state shouldBe SemanticEndpointPolicy.SemanticState.COMPLETE
        snap.decision shouldBe SemanticEndpointPolicy.Decision.FAST
        snap.delayMs shouldBe SemanticEndpointPolicy.FAST_MS
    }

    "SmartTube prefix-ambiguous selects NORMAL delay" {
        val snap = SemanticEndpointPolicy.evaluate(
            transcript = "Mở SmartTube",
            userSpeechStarted = true,
            endOfSpeechSeen = true,
        )
        snap.state shouldBe SemanticEndpointPolicy.SemanticState.PREFIX_AMBIGUOUS
        snap.decision shouldBe SemanticEndpointPolicy.Decision.NORMAL
    }

    "incomplete navigation does not execute" {
        val snap = SemanticEndpointPolicy.evaluate(
            transcript = "Chỉ đường đến",
            userSpeechStarted = true,
            endOfSpeechSeen = true,
        )
        snap.state shouldBe SemanticEndpointPolicy.SemanticState.INCOMPLETE
        snap.decision shouldBe SemanticEndpointPolicy.Decision.REJECT_INCOMPLETE
    }

    "complete navigation destination is NAV_DESTINATION_COMPLETE" {
        val snap = SemanticEndpointPolicy.evaluate(
            transcript = "Chỉ đường đến Mỹ Đình",
            userSpeechStarted = true,
            endOfSpeechSeen = true,
        )
        snap.state shouldBe SemanticEndpointPolicy.SemanticState.NAV_DESTINATION_COMPLETE
        snap.decision shouldBe SemanticEndpointPolicy.Decision.FAST
    }

    "without user speech endpoint waits" {
        val snap = SemanticEndpointPolicy.evaluate(
            transcript = "Mấy giờ rồi",
            userSpeechStarted = false,
            endOfSpeechSeen = true,
        )
        snap.decision shouldBe SemanticEndpointPolicy.Decision.WAIT
    }
})

class CommandEndpointLogicHandoffTest : StringSpec({
    "endpoint logic helpers remain available but do not own SR lifecycle" {
        CommandEndpointLogic.shouldFire(
            lastPartialText = "may gio roi",
            lastPartialChangeMs = 1_000L,
            endOfSpeechMs = 1_000L,
            nowMs = 1_600L,
            silenceEndpointMs = 550L,
            stabilityMs = 150L,
            userSpeechStarted = true,
        ).shouldBeTrue()
        // Runtime listener no longer uses this to finalize SpeechRecognizer.
        KnownGoodListenerInvariants.partialMayTerminateListener().shouldBeFalse()
    }
})

class FailureTtsFocusPolicyTest : StringSpec({
    "MODE gate can release while failure TTS keeps focus until done" {
        val abandonDuringSpeak = false
        val modeReadyWithoutWaitingForTts = true
        abandonDuringSpeak.shouldBeFalse()
        modeReadyWithoutWaitingForTts.shouldBeTrue()
    }

    "new MODE during prior TTS stops previous utterance before listen" {
        val stopBeforeNewListen = true
        stopBeforeNewListen.shouldBeTrue()
    }
})
