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

    "1. first COMPLETE Navigate semantic-commits; onPartial still does not lock" {
        StableCompletePartialTracker.bind(1L)
        val nav = u("Chỉ đường đến Mỹ Đình")
        nav.completeness shouldBe SemanticCompleteness.COMPLETE
        val obs = StableCompletePartialTracker.onPartial(1L, nav, 0L, 5L)
        obs.decision shouldBe StableCompletePartialTracker.Decision.COMMIT
        obs.reason shouldBe "semantic_navigate"
        obs.eosConfirmed.shouldBeFalse()
        SessionCommandDecision.bindSession(1L)
        SessionCommandDecision.onPartial(1L, nav)
        SessionCommandDecision.locked(1L).shouldBeNull()
        SessionCommandDecision.provisional(1L)!!.executable.shouldBeFalse()
        StableCompletePartialTracker.onTimer(1L, 10_000L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
    }

    "2. complete Navigate commits exactly once" {
        StableCompletePartialTracker.bind(1L)
        val nav = u("Chỉ đường đến Mỹ Đình")
        StableCompletePartialTracker.onPartial(1L, nav, 0L, 7L).decision shouldBe
            StableCompletePartialTracker.Decision.COMMIT
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

    "3. later destination change cannot commit a second Navigate" {
        StableCompletePartialTracker.bind(1L)
        val a = u("Chỉ đường đến Mỹ Đình")
        val b = u("Chỉ đường đến Hồ Gươm")
        StableCompletePartialTracker.onPartial(1L, a, 0L, 3L).decision shouldBe
            StableCompletePartialTracker.Decision.COMMIT
        StableCompletePartialTracker.onPartial(1L, b, 100L, 3L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
        StableCompletePartialTracker.onTimer(1L, 100L + 10_000L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
        StableCompletePartialPolicy.fingerprint(b) shouldBe "NAVIGATE|Hồ Gươm"
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
        StableCompletePartialTracker.onPartial(1L, nav, 0L, 7L).decision shouldBe
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

    "7. MODE cancel before first Navigate partial prevents action" {
        StableCompletePartialTracker.bind(7L)
        SessionCommandDecision.bindSession(7L)
        CanonicalActionGate.bind(7L)
        SessionCommandDecision.markCancelled(7L)
        CanonicalActionGate.markCancelled(7L)
        StableCompletePartialTracker.markCancelled()
        val nav = u("Chỉ đường đến Mỹ Đình", 7L)
        StableCompletePartialTracker.onPartial(7L, nav, 0L, 2L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
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
        StableCompletePartialTracker.onPartial(1L, nav, 0L, 11L).decision shouldBe
            StableCompletePartialTracker.Decision.COMMIT
        StableCompletePartialTracker.onPartial(1L, nav, 10L, 11L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
        StableCompletePartialTracker.onEndOfSpeech(1L, 20L, 10L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
        StableCompletePartialTracker.onPartial(1L, nav, 30L, 99L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
        StableCompletePartialTracker.onEndOfSpeech(1L, 40L, 11L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
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

    "11. NAVIGATE semantic-commits on first complete destination" {
        val nav = u("Chỉ đường đến Mỹ Đình")
        StableCompletePartialPolicy.isEligible(nav).shouldBeTrue()
        StableCompletePartialPolicy.isSemanticEarlyCommitSafe(nav).shouldBeTrue()
        StableCompletePartialPolicy.fingerprint(nav) shouldBe "NAVIGATE|Mỹ Đình"
        StableCompletePartialTracker.bind(1L)
        val obs = StableCompletePartialTracker.onPartial(1L, nav, 0L, 1L)
        obs.decision shouldBe StableCompletePartialTracker.Decision.COMMIT
        obs.reason shouldBe "semantic_navigate"
        obs.eosConfirmed.shouldBeFalse()
        StableCompletePartialTracker.onTimer(1L, 350L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
        StableCompletePartialTracker.onEndOfSpeech(1L, 20L, 1L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
        StableCompletePartialTracker.onPartial(1L, nav, 30L, 1L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
    }

    "12. OPEN_APP semantic commit on first complete catalog partial" {
        val open = u("Mở YouTube")
        open.command shouldBe CanonicalCommand.OpenApp("YouTube")
        StableCompletePartialPolicy.isEligible(open).shouldBeTrue()
        StableCompletePartialPolicy.isSemanticEarlyCommitSafe(open).shouldBeTrue()
        StableCompletePartialTracker.bind(2L)
        val obs = StableCompletePartialTracker.onPartial(2L, open, 0L, 4L)
        obs.decision shouldBe StableCompletePartialTracker.Decision.COMMIT
        obs.reason shouldBe "semantic_open_app"
        obs.eosConfirmed.shouldBeFalse()
        StableCompletePartialTracker.onPartial(2L, open, 10L, 4L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
        StableCompletePartialTracker.onEndOfSpeech(2L, 20L, 4L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
    }

    "13. PLAY_MEDIA semantic commit on complete query+provider" {
        val media = u("Mở bài Đừng xa em đêm nay trên YouTube")
        media.command shouldBe CanonicalCommand.PlayMedia("Đừng xa em đêm nay", "YouTube")
        StableCompletePartialPolicy.isEligible(media).shouldBeTrue()
        StableCompletePartialPolicy.isSemanticEarlyCommitSafe(media).shouldBeTrue()
        StableCompletePartialTracker.bind(3L)
        val obs = StableCompletePartialTracker.onPartial(3L, media, 0L, 6L)
        obs.decision shouldBe StableCompletePartialTracker.Decision.COMMIT
        obs.reason shouldBe "semantic_play_media"
        obs.eosConfirmed.shouldBeFalse()
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
        val generic = u("Mở FooBarApp")
        StableCompletePartialPolicy.isSemanticEarlyCommitSafe(generic).shouldBeFalse()
        StableCompletePartialTracker.onPartial(1L, generic, 1_000L, 1L).decision shouldBe
            StableCompletePartialTracker.Decision.WAIT
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
        firstFull.decision shouldBe StableCompletePartialTracker.Decision.COMMIT
        firstFull.reason shouldBe "semantic_navigate"
        firstFull.fingerprint shouldBe "NAVIGATE|Mỹ Đình"
        StableCompletePartialPolicy.isEligible(my).shouldBeFalse()
        StableCompletePartialPolicy.isEligible(full).shouldBeTrue()
    }
})
