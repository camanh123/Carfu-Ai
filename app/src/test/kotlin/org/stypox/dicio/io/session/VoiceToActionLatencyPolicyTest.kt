package org.stypox.dicio.io.session

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.stypox.dicio.io.input.CommandRecognitionPolicy

class VoiceToActionLatencyPolicyTest : StringSpec({
    "current execution waits for Android Final, not complete partials" {
        VoiceToActionLatencyPolicy.waitsForAndroidFinalBeforeExecute() shouldBe true
        VoiceToActionLatencyPolicy.executesFromCompletePartial() shouldBe false
        VoiceToActionLatencyPolicy.tryFastPartialExecutionEnabled() shouldBe false
        KnownGoodListenerInvariants.fastPartialRuntimeExecutionEnabled() shouldBe false
    }

    "product 5s timeout is no-speech only and does not apply after speech" {
        VoiceToActionLatencyPolicy.PRODUCT_NO_SPEECH_TIMEOUT_MS shouldBe 5_000L
        VoiceToActionLatencyPolicy.productSilenceAppliesAfterSpeechDetected() shouldBe false
        VoiceSessionManager.NO_SPEECH_TIMEOUT_MS shouldBe 5_000L
    }

    "confirmation TTS does not gate Android action" {
        VoiceToActionLatencyPolicy.waitsForConfirmationTtsBeforeAndroidAction() shouldBe false
        VoiceToActionLatencyPolicy.waitsForConfirmationTtsFinishBeforeAndroidAction() shouldBe false
    }

    "SpeechRecognizer silence extras are unset; MAX_SR_REARMS stays 0" {
        VoiceToActionLatencyPolicy.setsRecognizerSilenceExtras() shouldBe false
        VoiceToActionLatencyPolicy.maxSrRearms() shouldBe 0
        CommandRecognitionPolicy.MAX_SR_REARMS shouldBe 0
        KnownGoodListenerInvariants.usesGoogleSilenceIntentExtras() shouldBe false
        VoiceToActionLatencyPolicy.recognizerIntentExtrasCurrentlySet().shouldContainExactly(
            "EXTRA_LANGUAGE_MODEL",
            "EXTRA_LANGUAGE",
            "EXTRA_LANGUAGE_PREFERENCE",
            "EXTRA_PARTIAL_RESULTS",
            "EXTRA_MAX_RESULTS",
            "EXTRA_PREFER_OFFLINE",
            "EXTRA_CALLING_PACKAGE",
        )
        VoiceToActionLatencyPolicy.recognizerSilenceExtrasCurrentlyUnset().shouldContainExactly(
            "EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS",
            "EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS",
            "EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS",
        )
        VoiceToActionLatencyPolicy.recognizerSilenceExtrasLog() shouldContain "complete_silence=UNSET"
        VoiceToActionLatencyPolicy.recognizerSilenceExtrasLog() shouldContain "applied=false"
    }

    "complete Navigate understanding from a partial-shaped transcript is still not executable" {
        val u = VietnameseCommandUnderstanding.understand("Chỉ đường đến Mỹ Đình", 1L)
        u.completeness shouldBe SemanticCompleteness.COMPLETE
        u.command shouldBe CanonicalCommand.Navigate("Mỹ Đình")
        SessionCommandDecision.resetForTests()
        SessionCommandDecision.bindSession(1L)
        SessionCommandDecision.onPartial(1L, u)
        SessionCommandDecision.provisional(1L)!!.executable shouldBe false
        SessionCommandDecision.locked(1L) shouldBe null
        VoiceToActionLatencyPolicy.executesFromCompletePartial() shouldBe false
    }
})
