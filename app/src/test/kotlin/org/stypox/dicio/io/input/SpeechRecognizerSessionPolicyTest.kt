package org.stypox.dicio.io.input

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.stypox.dicio.io.session.CommandSessionPhase
import org.stypox.dicio.io.session.VoiceSessionManager

class SpeechRecognizerSessionPolicyTest : StringSpec({
    val productMs = VoiceSessionManager.NO_SPEECH_TIMEOUT_MS

    "MAX_SR_REARMS remains 0 — absorb is not a re-arm" {
        CommandRecognitionPolicy.MAX_SR_REARMS shouldBe 0
        CommandRecognitionPolicy.shouldRearmSpeechRecognizer(0).shouldBeFalse()
        CommandRecognitionPolicy.shouldRearmSpeechRecognizer(1).shouldBeFalse()
    }

    "error names map SpeechRecognizer numeric codes" {
        SpeechRecognizerSessionPolicy.errorName(6) shouldBe "ERROR_SPEECH_TIMEOUT"
        SpeechRecognizerSessionPolicy.errorName(7) shouldBe "ERROR_NO_MATCH"
        SpeechRecognizerSessionPolicy.errorName(5) shouldBe "ERROR_CLIENT"
        SpeechRecognizerSessionPolicy.errorName(8) shouldBe "ERROR_RECOGNIZER_BUSY"
        SpeechRecognizerSessionPolicy.errorName(9) shouldBe "ERROR_INSUFFICIENT_PERMISSIONS"
        SpeechRecognizerSessionPolicy.errorName(2) shouldBe "ERROR_NETWORK"
        SpeechRecognizerSessionPolicy.errorName(99) shouldBe "ERROR_UNKNOWN"
    }

    "stale generation never terminates the product session" {
        SpeechRecognizerSessionPolicy.onError(
            code = SpeechRecognizerSessionPolicy.ERROR_NO_MATCH,
            generationMatches = false,
            sawReady = true,
            sawSpeechOrPartial = false,
            elapsedMs = 50L,
            productTimeoutMs = productMs,
        ) shouldBe SpeechRecognizerSessionPolicy.ProductAction.IGNORE_STALE
        SpeechRecognizerSessionPolicy.onResults(
            utteranceCount = 1,
            generationMatches = false,
            sawSpeechOrPartial = true,
            elapsedMs = 50L,
            productTimeoutMs = productMs,
        ) shouldBe SpeechRecognizerSessionPolicy.ProductAction.IGNORE_STALE
        SpeechRecognizerSessionPolicy.onPartial(
            generationMatches = false,
            textBlank = false,
        ).shouldBeFalse()
    }

    "early NO_MATCH / SPEECH_TIMEOUT / CLIENT keep the product session" {
        val earlyCodes = listOf(
            SpeechRecognizerSessionPolicy.ERROR_NO_MATCH,
            SpeechRecognizerSessionPolicy.ERROR_SPEECH_TIMEOUT,
            SpeechRecognizerSessionPolicy.ERROR_CLIENT,
            SpeechRecognizerSessionPolicy.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizerSessionPolicy.ERROR_NETWORK,
        )
        earlyCodes.forEach { code ->
            SpeechRecognizerSessionPolicy.onError(
                code = code,
                generationMatches = true,
                sawReady = false,
                sawSpeechOrPartial = false,
                elapsedMs = 80L,
                productTimeoutMs = productMs,
            ) shouldBe SpeechRecognizerSessionPolicy.ProductAction.KEEP_PRODUCT_SESSION
        }
    }

    "empty onResults before speech keeps the product session" {
        SpeechRecognizerSessionPolicy.onResults(
            utteranceCount = 0,
            generationMatches = true,
            sawSpeechOrPartial = false,
            elapsedMs = 40L,
            productTimeoutMs = productMs,
        ) shouldBe SpeechRecognizerSessionPolicy.ProductAction.KEEP_PRODUCT_SESSION
    }

    "non-empty finals still process" {
        SpeechRecognizerSessionPolicy.onResults(
            utteranceCount = 2,
            generationMatches = true,
            sawSpeechOrPartial = true,
            elapsedMs = 900L,
            productTimeoutMs = productMs,
        ) shouldBe SpeechRecognizerSessionPolicy.ProductAction.PROCESS_FINAL
    }

    "NO_MATCH after speech/partial is product no-speech (rescue path), not a re-arm" {
        SpeechRecognizerSessionPolicy.onError(
            code = SpeechRecognizerSessionPolicy.ERROR_NO_MATCH,
            generationMatches = true,
            sawReady = true,
            sawSpeechOrPartial = true,
            elapsedMs = 1_200L,
            productTimeoutMs = productMs,
        ) shouldBe SpeechRecognizerSessionPolicy.ProductAction.TERMINATE_NO_SPEECH
    }

    "unrecoverable permission / audio / language still terminate" {
        listOf(
            SpeechRecognizerSessionPolicy.ERROR_INSUFFICIENT_PERMISSIONS,
            SpeechRecognizerSessionPolicy.ERROR_AUDIO,
            SpeechRecognizerSessionPolicy.ERROR_LANGUAGE_NOT_SUPPORTED,
        ).forEach { code ->
            SpeechRecognizerSessionPolicy.onError(
                code = code,
                generationMatches = true,
                sawReady = false,
                sawSpeechOrPartial = false,
                elapsedMs = 10L,
                productTimeoutMs = productMs,
            ) shouldBe SpeechRecognizerSessionPolicy.ProductAction.TERMINATE_UNRECOVERABLE
        }
    }

    "product silence timeout elapsed maps recognizer end to no-speech terminal" {
        SpeechRecognizerSessionPolicy.onError(
            code = SpeechRecognizerSessionPolicy.ERROR_NO_MATCH,
            generationMatches = true,
            sawReady = true,
            sawSpeechOrPartial = false,
            elapsedMs = productMs,
            productTimeoutMs = productMs,
        ) shouldBe SpeechRecognizerSessionPolicy.ProductAction.TERMINATE_NO_SPEECH
    }

    "onEndOfSpeech is never a product terminal" {
        SpeechRecognizerSessionPolicy.endOfSpeechTerminatesProduct().shouldBeFalse()
    }

    "COMMAND_LISTENING is entered before startListening so the cue cannot fight the mic" {
        SpeechRecognizerSessionPolicy.markCommandListeningBeforeStartListening().shouldBeTrue()
        ListeningCueSafety.shouldPlayListeningCue(
            becameListening = true,
            phase = CommandSessionPhase.COMMAND_LISTENING,
        ).shouldBeFalse()
    }

    "product 5s silence timeout still owns no-speech" {
        VoiceSessionManager.NO_SPEECH_TIMEOUT_MS shouldBe 5_000L
        CommandRecognitionPolicy.ANDROID_NO_SPEECH_AFTER_READY_MS shouldBe 0L
    }
})
