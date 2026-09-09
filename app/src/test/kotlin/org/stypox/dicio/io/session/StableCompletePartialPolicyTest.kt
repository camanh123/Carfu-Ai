package org.stypox.dicio.io.session

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.longs.shouldBeExactly
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

    "1. first COMPLETE partial does not execute without stability" {
        StableCompletePartialTracker.bind(1L)
        val nav = u("Chỉ đường đến Mỹ Đình")
        nav.completeness shouldBe SemanticCompleteness.COMPLETE
        val obs = StableCompletePartialTracker.onPartial(1L, nav, 0L)
        obs.decision shouldBe StableCompletePartialTracker.Decision.WAIT
        obs.remainingMs shouldBeExactly StableCompletePartialPolicy.STABILITY_MS
        obs.consecutive shouldBe 1
        SessionCommandDecision.bindSession(1L)
        SessionCommandDecision.onPartial(1L, nav)
        SessionCommandDecision.locked(1L).shouldBeNull()
        SessionCommandDecision.provisional(1L)!!.executable.shouldBeFalse()
    }

    "2. repeated stable COMPLETE partial commits exactly once" {
        StableCompletePartialTracker.bind(1L)
        val nav = u("Chỉ đường đến Mỹ Đình")
        StableCompletePartialTracker.onPartial(1L, nav, 0L).decision shouldBe
            StableCompletePartialTracker.Decision.WAIT
        val second = StableCompletePartialTracker.onPartial(1L, nav, 40L)
        second.decision shouldBe StableCompletePartialTracker.Decision.COMMIT
        second.reason shouldBe "consecutive=2"
        StableCompletePartialTracker.onPartial(1L, nav, 80L).decision shouldBe
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

    "3. changed partial resets stability" {
        StableCompletePartialTracker.bind(1L)
        val a = u("Chỉ đường đến Mỹ Đình")
        val b = u("Chỉ đường đến Hồ Gươm")
        StableCompletePartialTracker.onPartial(1L, a, 0L).decision shouldBe
            StableCompletePartialTracker.Decision.WAIT
        val changed = StableCompletePartialTracker.onPartial(1L, b, 100L)
        changed.decision shouldBe StableCompletePartialTracker.Decision.WAIT
        changed.consecutive shouldBe 1
        changed.fingerprint shouldBe "NAVIGATE|Hồ Gươm"
        StableCompletePartialTracker.onTimer(1L, 100L + 349L).decision shouldBe
            StableCompletePartialTracker.Decision.WAIT
        StableCompletePartialTracker.onTimer(1L, 100L + 350L).decision shouldBe
            StableCompletePartialTracker.Decision.COMMIT
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
            val obs = StableCompletePartialTracker.onPartial(1L, u(raw), 0L)
            obs.decision shouldBe StableCompletePartialTracker.Decision.IGNORE
            StableCompletePartialPolicy.isEligible(u(raw)).shouldBeFalse()
        }
    }

    "5. later final after early commit does not execute twice" {
        StableCompletePartialTracker.bind(1L)
        SessionCommandDecision.bindSession(1L)
        CanonicalActionGate.bind(1L)
        val nav = u("Chỉ đường đến Mỹ Đình")
        StableCompletePartialTracker.onPartial(1L, nav, 0L)
        StableCompletePartialTracker.onPartial(1L, nav, 10L).decision shouldBe
            StableCompletePartialTracker.Decision.COMMIT
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
        StableCompletePartialTracker.onPartial(7L, nav, 0L).decision shouldBe
            StableCompletePartialTracker.Decision.WAIT
        SessionCommandDecision.markCancelled(7L)
        CanonicalActionGate.markCancelled(7L)
        StableCompletePartialTracker.markCancelled()
        StableCompletePartialTracker.onTimer(7L, 400L).decision shouldBe
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

    "11. NAVIGATE fast path" {
        val nav = u("Chỉ đường đến Mỹ Đình")
        StableCompletePartialPolicy.isEligible(nav).shouldBeTrue()
        StableCompletePartialPolicy.fingerprint(nav) shouldBe "NAVIGATE|Mỹ Đình"
        StableCompletePartialTracker.bind(1L)
        StableCompletePartialTracker.onPartial(1L, nav, 0L)
        StableCompletePartialTracker.onTimer(1L, 350L).decision shouldBe
            StableCompletePartialTracker.Decision.COMMIT
    }

    "12. OPEN_APP fast path" {
        val open = u("Mở YouTube")
        open.command shouldBe CanonicalCommand.OpenApp("YouTube")
        StableCompletePartialPolicy.isEligible(open).shouldBeTrue()
        StableCompletePartialTracker.bind(2L)
        StableCompletePartialTracker.onPartial(2L, open, 0L)
        StableCompletePartialTracker.onPartial(2L, open, 20L).decision shouldBe
            StableCompletePartialTracker.Decision.COMMIT
    }

    "13. PLAY_MEDIA fast path" {
        val media = u("Mở bài Đừng xa em đêm nay trên YouTube")
        media.command shouldBe CanonicalCommand.PlayMedia("Đừng xa em đêm nay", "YouTube")
        StableCompletePartialPolicy.isEligible(media).shouldBeTrue()
        StableCompletePartialTracker.bind(3L)
        StableCompletePartialTracker.onPartial(3L, media, 0L).decision shouldBe
            StableCompletePartialTracker.Decision.WAIT
        StableCompletePartialTracker.onPartial(3L, media, 15L).decision shouldBe
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
    }

    "stability timer does not commit before STABILITY_MS" {
        StableCompletePartialTracker.bind(1L)
        val nav = u("Chỉ đường đến Mỹ Đình")
        StableCompletePartialTracker.onPartial(1L, nav, 1_000L)
        StableCompletePartialTracker.onTimer(1L, 1_349L).decision shouldBe
            StableCompletePartialTracker.Decision.WAIT
        val due = StableCompletePartialTracker.onTimer(1L, 1_350L)
        due.decision shouldBe StableCompletePartialTracker.Decision.COMMIT
        due.reason shouldBe "held_ms=350"
    }

    "stopListening after EOS remains disabled" {
        StableCompletePartialPolicy.callsStopListeningAfterEndOfSpeech().shouldBeFalse()
        SpeechRecognizerSessionPolicy.endOfSpeechTerminatesProduct().shouldBeFalse()
    }
})
