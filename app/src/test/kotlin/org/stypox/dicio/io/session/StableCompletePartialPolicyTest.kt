package org.stypox.dicio.io.session

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.stypox.dicio.io.input.SpeechRecognizerSessionPolicy

class StableCompletePartialPolicyTest : StringSpec({
    beforeTest {
        StableCompletePartialTracker.resetForTests()
        SessionCommandDecision.resetForTests()
        CanonicalActionGate.resetForTests()
        CommandSessionOutcome.resetForTests()
        VoiceSessionManager.resetForTests()
    }

    fun u(raw: String, sid: Long = 1L) =
        VietnameseCommandUnderstanding.understand(raw, sessionId = sid)

    fun waitThenEosCommit(
        sid: Long,
        result: UnderstandingResult,
        generation: Long = 7L,
    ): StableCompletePartialTracker.Observe {
        StableCompletePartialTracker.onPartial(sid, result, 0L, generation).decision shouldBe
            StableCompletePartialTracker.Decision.WAIT
        StableCompletePartialTracker.onPartial(sid, result, 10L, generation).decision shouldBe
            StableCompletePartialTracker.Decision.WAIT
        return StableCompletePartialTracker.onEndOfSpeech(sid, 20L, generation)
    }

    "1. first COMPLETE partial does not execute without consecutive+EOS" {
        StableCompletePartialTracker.bind(1L)
        val nav = u("Chỉ đường đến Mỹ Đình")
        nav.completeness shouldBe SemanticCompleteness.COMPLETE
        val obs = StableCompletePartialTracker.onPartial(1L, nav, 0L, 5L)
        obs.decision shouldBe StableCompletePartialTracker.Decision.WAIT
        obs.remainingMs shouldBe 0L
        obs.consecutive shouldBe 1
        obs.eosConfirmed.shouldBeFalse()
        SessionCommandDecision.bindSession(1L)
        SessionCommandDecision.onPartial(1L, nav)
        SessionCommandDecision.locked(1L).shouldBeNull()
        SessionCommandDecision.provisional(1L)!!.executable.shouldBeFalse()
        StableCompletePartialTracker.onTimer(1L, 10_000L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
    }

    "2. two identical COMPLETE partials plus EOS commits exactly once" {
        StableCompletePartialTracker.bind(1L)
        val nav = u("Chỉ đường đến Mỹ Đình")
        waitThenEosCommit(1L, nav).decision shouldBe StableCompletePartialTracker.Decision.COMMIT
        StableCompletePartialTracker.onPartial(1L, nav, 80L, 7L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE

        SessionCommandDecision.bindSession(1L)
        CanonicalActionGate.bind(1L)
        val locked = SessionCommandDecision.lockFinal(1L, nav.copy(executable = true))
        locked!!.command shouldBe CanonicalCommand.Navigate("Mỹ Đình")
        CanonicalActionGate.tryClaim(1L).shouldBeTrue()
        CanonicalActionGate.tryClaim(1L).shouldBeFalse()
        CommandSessionOutcome.claim(CommandSessionOutcome.Kind.EXECUTED).shouldBeTrue()
        CommandSessionOutcome.claim(CommandSessionOutcome.Kind.EXECUTED).shouldBeFalse()
    }

    "3. changed partial resets stability and hold timer cannot commit" {
        StableCompletePartialTracker.bind(1L)
        val a = u("Chỉ đường đến Mỹ Đình")
        val b = u("Chỉ đường đến Hồ Gươm")
        StableCompletePartialTracker.onPartial(1L, a, 0L, 3L).decision shouldBe
            StableCompletePartialTracker.Decision.WAIT
        val changed = StableCompletePartialTracker.onPartial(1L, b, 100L, 3L)
        changed.decision shouldBe StableCompletePartialTracker.Decision.WAIT
        changed.consecutive shouldBe 1
        changed.fingerprint shouldBe "NAVIGATE|Hồ Gươm"
        StableCompletePartialTracker.onTimer(1L, 100L + 10_000L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
        StableCompletePartialTracker.onPartial(1L, b, 120L, 3L).decision shouldBe
            StableCompletePartialTracker.Decision.WAIT
        val eos = StableCompletePartialTracker.onEndOfSpeech(1L, 140L, 3L)
        eos.decision shouldBe StableCompletePartialTracker.Decision.COMMIT
        eos.fingerprint shouldBe "NAVIGATE|Hồ Gươm"
    }

    "4. incomplete partial never executes" {
        StableCompletePartialTracker.bind(1L)
        listOf(
            "chỉ đường đến",
            "mở bài",
            "mở bài đừng",
            "mở",
            "gọi",
        ).forEach { raw ->
            val obs = StableCompletePartialTracker.onPartial(1L, u(raw), 0L, 1L)
            obs.decision shouldBe StableCompletePartialTracker.Decision.IGNORE
            StableCompletePartialPolicy.isEligible(u(raw)).shouldBeFalse()
        }
    }

    "5. later final after early commit does not execute twice" {
        StableCompletePartialTracker.bind(1L)
        SessionCommandDecision.bindSession(1L)
        CanonicalActionGate.bind(1L)
        val nav = u("Chỉ đường đến Mỹ Đình")
        waitThenEosCommit(1L, nav).decision shouldBe StableCompletePartialTracker.Decision.COMMIT
        val locked = SessionCommandDecision.lockFinal(1L, nav.copy(executable = true))!!
        CanonicalActionGate.tryClaim(1L).shouldBeTrue()
        CommandSessionOutcome.claim(CommandSessionOutcome.Kind.EXECUTED).shouldBeTrue()

        val late = SessionCommandDecision.decideFinal(
            1L,
            listOf("Chỉ đường đến Mỹ Đình" to 1f),
        )
        late!!.command shouldBe locked.command
        CanonicalActionGate.tryClaim(1L).shouldBeFalse()
        CommandSessionOutcome.peek() shouldBe CommandSessionOutcome.Kind.EXECUTED
        CommandSessionOutcome.claim(CommandSessionOutcome.Kind.NO_SPEECH).shouldBeFalse()
    }

    "6. late onError after early commit cannot damage session" {
        SpeechRecognizerSessionPolicy.onError(
            code = SpeechRecognizerSessionPolicy.ERROR_CLIENT,
            generationMatches = false,
            sawReady = true,
            sawSpeechOrPartial = true,
            elapsedMs = 8_000L,
            productTimeoutMs = VoiceSessionManager.NO_SPEECH_TIMEOUT_MS,
        ) shouldBe SpeechRecognizerSessionPolicy.ProductAction.IGNORE_STALE
        CommandSessionOutcome.claim(CommandSessionOutcome.Kind.EXECUTED).shouldBeTrue()
        CommandSessionOutcome.claim(CommandSessionOutcome.Kind.SR_ERROR).shouldBeFalse()
        CommandSessionOutcome.claim(CommandSessionOutcome.Kind.NO_SPEECH).shouldBeFalse()
    }

    "7. MODE cancel before stable commit prevents action" {
        StableCompletePartialTracker.bind(7L)
        SessionCommandDecision.bindSession(7L)
        CanonicalActionGate.bind(7L)
        val nav = u("Chỉ đường đến Mỹ Đình", 7L)
        StableCompletePartialTracker.onPartial(7L, nav, 0L, 2L).decision shouldBe
            StableCompletePartialTracker.Decision.WAIT
        SessionCommandDecision.markCancelled(7L)
        CanonicalActionGate.markCancelled(7L)
        StableCompletePartialTracker.markCancelled()
        StableCompletePartialTracker.onTimer(7L, 400L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
        StableCompletePartialTracker.onEndOfSpeech(7L, 400L, 2L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
        SessionCommandDecision.lockFinal(7L, nav.copy(executable = true)).shouldBeNull()
        CanonicalActionGate.tryClaim(7L).shouldBeFalse()
    }

    "8. stale generation ignored" {
        SpeechRecognizerSessionPolicy.onPartial(
            generationMatches = false,
            textBlank = false,
        ).shouldBeFalse()
        SpeechRecognizerSessionPolicy.onResults(
            utteranceCount = 1,
            generationMatches = false,
            sawSpeechOrPartial = true,
            elapsedMs = 100L,
            productTimeoutMs = VoiceSessionManager.NO_SPEECH_TIMEOUT_MS,
        ) shouldBe SpeechRecognizerSessionPolicy.ProductAction.IGNORE_STALE
        StableCompletePartialTracker.bind(1L)
        val nav = u("Chỉ đường đến Mỹ Đình")
        StableCompletePartialTracker.onPartial(1L, nav, 0L, 11L)
        StableCompletePartialTracker.onPartial(1L, nav, 10L, 11L)
        StableCompletePartialTracker.onEndOfSpeech(1L, 20L, 10L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
        StableCompletePartialTracker.onPartial(1L, nav, 30L, 99L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
        StableCompletePartialTracker.onEndOfSpeech(1L, 40L, 11L).decision shouldBe
            StableCompletePartialTracker.Decision.COMMIT
    }

    "9. silence/no-speech Phase 4.2 behavior unchanged" {
        VoiceSessionManager.NO_SPEECH_TIMEOUT_MS shouldBe 5_000L
        SpeechRecognizerSessionPolicy.endOfSpeechTerminatesProduct().shouldBeFalse()
        val keep = SpeechRecognizerSessionPolicy.onError(
            code = SpeechRecognizerSessionPolicy.ERROR_NO_MATCH,
            generationMatches = true,
            sawReady = true,
            sawSpeechOrPartial = false,
            elapsedMs = 50L,
            productTimeoutMs = VoiceSessionManager.NO_SPEECH_TIMEOUT_MS,
        )
        keep shouldBe SpeechRecognizerSessionPolicy.ProductAction.KEEP_PRODUCT_SESSION
    }

    "10. transient SR error policy unchanged" {
        SpeechRecognizerSessionPolicy.onError(
            code = SpeechRecognizerSessionPolicy.ERROR_CLIENT,
            generationMatches = true,
            sawReady = false,
            sawSpeechOrPartial = false,
            elapsedMs = 20L,
            productTimeoutMs = VoiceSessionManager.NO_SPEECH_TIMEOUT_MS,
        ) shouldBe SpeechRecognizerSessionPolicy.ProductAction.KEEP_PRODUCT_SESSION
        org.stypox.dicio.io.input.CommandRecognitionPolicy.MAX_SR_REARMS shouldBe 0
        org.stypox.dicio.io.input.CommandRecognitionPolicy.shouldRearmSpeechRecognizer(0)
            .shouldBeFalse()
    }

    "11. NAVIGATE fast path requires two identical COMPLETE + EOS" {
        val nav = u("Chỉ đường đến Mỹ Đình")
        StableCompletePartialPolicy.isEligible(nav).shouldBeTrue()
        StableCompletePartialPolicy.fingerprint(nav) shouldBe "NAVIGATE|Mỹ Đình"
        StableCompletePartialTracker.bind(1L)
        StableCompletePartialTracker.onPartial(1L, nav, 0L, 1L)
        StableCompletePartialTracker.onTimer(1L, 350L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
        StableCompletePartialTracker.onEndOfSpeech(1L, 20L, 1L).decision shouldBe
            StableCompletePartialTracker.Decision.WAIT
        StableCompletePartialTracker.onPartial(1L, nav, 30L, 1L).decision shouldBe
            StableCompletePartialTracker.Decision.COMMIT
    }

    "12. OPEN_APP fast path" {
        val open = u("Mở YouTube")
        open.command shouldBe CanonicalCommand.OpenApp("YouTube")
        StableCompletePartialPolicy.isEligible(open).shouldBeTrue()
        StableCompletePartialTracker.bind(2L)
        waitThenEosCommit(2L, open, generation = 4L).decision shouldBe
            StableCompletePartialTracker.Decision.COMMIT
    }

    "13. PLAY_MEDIA fast path" {
        val media = u("Mở bài Đừng xa em đêm nay trên YouTube")
        media.command shouldBe CanonicalCommand.PlayMedia("Đừng xa em đêm nay", "YouTube")
        StableCompletePartialPolicy.isEligible(media).shouldBeTrue()
        StableCompletePartialTracker.bind(3L)
        waitThenEosCommit(3L, media, generation = 6L).decision shouldBe
            StableCompletePartialTracker.Decision.COMMIT
    }

    "14. CALL/contact commands remain excluded" {
        val incomplete = u("gọi")
        StableCompletePartialPolicy.isEligible(incomplete).shouldBeFalse()
        val call = u("gọi mẹ")
        if (call.command is CanonicalCommand.CallContact) {
            StableCompletePartialPolicy.isEligible(call).shouldBeFalse()
        }
        val completeCall = VietnameseCommandUnderstanding.understand("gọi Nam", 4L)
        if (completeCall.completeness == SemanticCompleteness.COMPLETE) {
            StableCompletePartialPolicy.isEligible(completeCall).shouldBeFalse()
        }
        StableCompletePartialTracker.bind(4L)
        StableCompletePartialTracker.onPartial(4L, completeCall, 0L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
        StableCompletePartialTracker.onPartial(4L, completeCall, 10L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
        StableCompletePartialTracker.onEndOfSpeech(4L, 20L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
    }

    "hold timer is disabled and cannot commit" {
        StableCompletePartialPolicy.holdTimerMayCommit().shouldBeFalse()
        StableCompletePartialPolicy.STABILITY_MS shouldBe 0L
        StableCompletePartialTracker.bind(1L)
        val nav = u("Chỉ đường đến Mỹ Đình")
        StableCompletePartialTracker.onPartial(1L, nav, 1_000L, 1L)
        StableCompletePartialTracker.onTimer(1L, 1_350L).let { due ->
            due.decision shouldBe StableCompletePartialTracker.Decision.IGNORE
            due.reason shouldBe "hold_timer_disabled"
        }
        StableCompletePartialTracker.hasPendingStabilityWork().shouldBeTrue()
    }

    "stopListening after EOS remains disabled" {
        StableCompletePartialPolicy.callsStopListeningAfterEndOfSpeech().shouldBeFalse()
        SpeechRecognizerSessionPolicy.endOfSpeechTerminatesProduct().shouldBeFalse()
    }

    "single-token Navigate destination is not fast-path eligible" {
        val my = u("chỉ đường đến Mỹ")
        my.completeness shouldBe SemanticCompleteness.COMPLETE
        my.command shouldBe CanonicalCommand.Navigate("Mỹ")
        StableCompletePartialPolicy.isEligible(my).shouldBeFalse()
        StableCompletePartialTracker.bind(1L)
        StableCompletePartialTracker.onPartial(1L, my, 0L, 1L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
        StableCompletePartialTracker.onPartial(1L, my, 10L, 1L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
        StableCompletePartialTracker.onEndOfSpeech(1L, 20L, 1L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
        SessionCommandDecision.bindSession(1L)
        SessionCommandDecision.onPartial(1L, my)
        SessionCommandDecision.locked(1L).shouldBeNull()
    }

    "Mỹ then Mỹ Đình must not lock Mỹ" {
        StableCompletePartialTracker.bind(1L)
        val my = u("chỉ đường đến Mỹ")
        val full = u("chỉ đường đến Mỹ Đình")
        StableCompletePartialTracker.onPartial(1L, my, 0L, 1L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
        StableCompletePartialTracker.onEndOfSpeech(1L, 400L, 1L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
        val firstFull = StableCompletePartialTracker.onPartial(1L, full, 500L, 1L)
        firstFull.decision shouldBe StableCompletePartialTracker.Decision.WAIT
        firstFull.fingerprint shouldBe "NAVIGATE|Mỹ Đình"
        firstFull.consecutive shouldBe 1
        StableCompletePartialPolicy.isEligible(my).shouldBeFalse()
        StableCompletePartialPolicy.isEligible(full).shouldBeTrue()
    }
})
