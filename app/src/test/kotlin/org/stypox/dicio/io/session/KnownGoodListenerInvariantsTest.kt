package org.stypox.dicio.io.session

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.stypox.dicio.io.input.CommandRecognitionPolicy
import org.stypox.dicio.io.input.SpeechRecognizerSessionPolicy

class KnownGoodListenerInvariantsTest : StringSpec({
    "A: one MODE yields exactly one startListening" {
        KnownGoodListenerInvariants.startListeningCountForModePress() shouldBe 1
        KnownGoodListenerInvariants.startListeningCountForModePress(rearmAttempts = 2) shouldBe 1
        CommandRecognitionPolicy.MAX_SR_REARMS shouldBe 0
        CommandRecognitionPolicy.shouldRearmSpeechRecognizer(0).shouldBeFalse()
        CommandRecognitionPolicy.shouldRearmSpeechRecognizer(1).shouldBeFalse()
    }

    "B: BOS/EOS do not re-arm" {
        KnownGoodListenerInvariants.bosOrEosMayRearm().shouldBeFalse()
        CommandListenHandoffPolicy.shouldRearmAfterNoSpeechError(
            handoffComplete = true,
            confirmedUserSpeech = false,
            rearmCount = 0,
        ).shouldBeFalse()
    }

    "C: NO_MATCH does not retry SpeechRecognizer" {
        KnownGoodListenerInvariants.noMatchMayRetry().shouldBeFalse()
        CommandRecognitionPolicy.isNoSpeechError(7).shouldBeTrue()
        SpeechRecognizerSessionPolicy.onError(
            code = 7,
            generationMatches = true,
            sawReady = false,
            sawSpeechOrPartial = false,
            elapsedMs = 50L,
            productTimeoutMs = VoiceSessionManager.NO_SPEECH_TIMEOUT_MS,
        ) shouldBe SpeechRecognizerSessionPolicy.ProductAction.KEEP_PRODUCT_SESSION
    }

    "D: SPEECH_TIMEOUT does not retry SpeechRecognizer" {
        KnownGoodListenerInvariants.speechTimeoutMayRetry().shouldBeFalse()
        CommandRecognitionPolicy.isNoSpeechError(6).shouldBeTrue()
        SpeechRecognizerSessionPolicy.onError(
            code = 6,
            generationMatches = true,
            sawReady = false,
            sawSpeechOrPartial = false,
            elapsedMs = 50L,
            productTimeoutMs = VoiceSessionManager.NO_SPEECH_TIMEOUT_MS,
        ) shouldBe SpeechRecognizerSessionPolicy.ProductAction.KEEP_PRODUCT_SESSION
    }

    "E: partials must not terminate the listener; unstable first-partial execute stays off" {
        KnownGoodListenerInvariants.partialMayTerminateListener().shouldBeFalse()
        KnownGoodListenerInvariants.fastPartialRuntimeExecutionEnabled().shouldBeFalse()
        KnownGoodListenerInvariants.stableCompletePartialFastPathEnabled().shouldBeTrue()
    }

    "F: final n-best still routes through Smart matcher exactly once" {
        CommandSessionOutcome.reset()
        val first = CommandSessionOutcome.claim(CommandSessionOutcome.Kind.EXECUTED)
        val second = CommandSessionOutcome.claim(CommandSessionOutcome.Kind.EXECUTED)
        first.shouldBeTrue()
        second.shouldBeFalse()
        val match = CarfuCommandRouter.matchBest(
            listOf("Mấy giờ rồi" to 0.9f, "may gio roi" to 0.4f),
        )
        match!!.command.intent shouldBe CarfuIntent.CURRENT_TIME
    }

    "G: late error after final is ignored by Outcome" {
        CommandSessionOutcome.reset()
        CommandSessionOutcome.claim(CommandSessionOutcome.Kind.EXECUTED).shouldBeTrue()
        CommandSessionOutcome.claim(CommandSessionOutcome.Kind.SR_ERROR).shouldBeFalse()
        CommandSessionOutcome.claim(CommandSessionOutcome.Kind.NO_SPEECH).shouldBeFalse()
    }

    "H: background ON + wake OFF + no MODE → zero command listener starts" {
        KnownGoodListenerInvariants.backgroundWakeOffMayStartCommandListener(
            backgroundServiceAlive = true,
            wakeWordEnabled = false,
            freshModeOrUiAssist = false,
        ).shouldBeFalse()
        CarfuSessionGate.resetForTests()
        CarfuSessionGate.setBackgroundWakeEnabled(false)
        val auto = CarfuSessionGate.requestStart(
            origin = CarfuSessionGate.Origin.WAKE_WORD,
            phase = CommandSessionPhase.IDLE_WAKE,
            startSession = { 99L },
        )
        auto.accepted.shouldBeFalse()
        auto.decision shouldBe CarfuSessionGate.Decision.REJECTED_WAKE_OFF
    }

    "I: session ended + simulated >15s → no automatic restart" {
        KnownGoodListenerInvariants.automaticRestartAfterSessionEndMs(16_000L).shouldBeFalse()
        KnownGoodListenerInvariants.automaticRestartAfterSessionEndMs(60_000L).shouldBeFalse()
    }

    "J: hardware MODE still starts exactly one session" {
        CarfuSessionGate.resetForTests()
        CarfuSessionGate.setBackgroundWakeEnabled(false)
        val mode = CarfuSessionGate.requestStart(
            origin = CarfuSessionGate.Origin.HARDWARE_BUTTON,
            phase = CommandSessionPhase.IDLE_WAKE,
            intentAction = "android.intent.action.ASSIST",
            startSession = { 7L },
        )
        mode.accepted.shouldBeTrue()
        mode.sessionId shouldBe 7L
        KnownGoodListenerInvariants.MAX_COMMAND_SESSION_PER_MODE shouldBe 1
        KnownGoodListenerInvariants.START_LISTENING_PER_MODE shouldBe 1
    }

    "hard listen failsafe is ~12s; app-side 3s killer is removed" {
        KnownGoodListenerInvariants.HARD_LISTEN_CEILING_MS shouldBe 12_000L
        CommandRecognitionPolicy.ANDROID_LISTEN_TIMEOUT_MS shouldBe 12_000L
        KnownGoodListenerInvariants.appSideNoSpeechKillerMs() shouldBe 0L
        KnownGoodListenerInvariants.usesGoogleSilenceIntentExtras().shouldBeFalse()
    }

    "one-MODE max counts are single-flight" {
        KnownGoodListenerInvariants.MAX_ACK_PER_MODE shouldBe 0
        KnownGoodListenerInvariants.MAX_ACTIVE_RECOGNIZERS shouldBe 1
        KnownGoodListenerInvariants.MAX_EXECUTIONS_PER_MODE shouldBe 1
        KnownGoodListenerInvariants.MAX_FAILURE_TTS_PER_MODE shouldBe 0
        KnownGoodListenerInvariants.modeUsesSpokenAck().shouldBeFalse()
        KnownGoodListenerInvariants.noSpeechExitsSilently().shouldBeTrue()
        KnownGoodListenerInvariants.PRODUCT_NO_SPEECH_TIMEOUT_MS shouldBe 5_000L
    }
})
