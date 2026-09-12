package org.stypox.dicio.io.session

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.stypox.dicio.io.input.CommandRecognitionPolicy
import org.stypox.dicio.io.input.SpeechRecognizerSessionPolicy
import org.stypox.dicio.skills.carfu.nlu.NavigationCommitPolicy

/**
 * Phase 4.3B.1 required regression matrix. Each case is JVM-source-proven against
 * the policy objects that own session lifetime / fast-path / 4.2 absorb rules.
 */
class Phase431PreSpeechAndStabilityRegressionTest : StringSpec({
    beforeTest {
        StableCompletePartialTracker.resetForTests()
        SessionCommandDecision.resetForTests()
        CanonicalActionGate.resetForTests()
        CommandSessionOutcome.resetForTests()
        VoiceSessionManager.resetForTests()
    }

    fun u(raw: String, sid: Long = 1L) =
        VietnameseCommandUnderstanding.understand(raw, sessionId = sid)

    fun productTimeout() = VoiceSessionManager.NO_SPEECH_TIMEOUT_MS

    "PRE-SPEECH 1-2. MIC/MODE with no callbacks leave fast-path inert and outcome OPEN" {
        StableCompletePartialTracker.bind(11L)
        SessionCommandDecision.bindSession(11L)
        CanonicalActionGate.bind(11L)
        CommandSessionOutcome.reset()
        StableCompletePartialTracker.hasPendingStabilityWork().shouldBeFalse()
        StableCompletePartialTracker.eosConfirmedForTests().shouldBeFalse()
        SessionCommandDecision.locked(11L).shouldBeNull()
        SessionCommandDecision.provisional(11L).shouldBeNull()
        CanonicalActionGate.tryClaim(11L).shouldBeTrue() // gate is session-scoped, not command-scoped
        CanonicalActionGate.resetForTests()
        CanonicalActionGate.bind(11L)
        CommandSessionOutcome.peek() shouldBe CommandSessionOutcome.Kind.OPEN
        StableCompletePartialTracker.onTimer(11L, 1L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
        VoiceSessionManager.NO_SPEECH_TIMEOUT_MS shouldBe 5_000L
    }

    "PRE-SPEECH 3. onReadyForSpeech is not a product terminal" {
        SpeechRecognizerSessionPolicy.endOfSpeechTerminatesProduct().shouldBeFalse()
        SpeechRecognizerSessionPolicy.onError(
            code = SpeechRecognizerSessionPolicy.ERROR_NO_MATCH,
            generationMatches = true,
            sawReady = true,
            sawSpeechOrPartial = false,
            elapsedMs = 30L,
            productTimeoutMs = productTimeout(),
        ) shouldBe SpeechRecognizerSessionPolicy.ProductAction.KEEP_PRODUCT_SESSION
    }

    "PRE-SPEECH 4. RMS-only cannot arm fast-path (no partial → no tracker work)" {
        StableCompletePartialTracker.bind(12L)
        StableCompletePartialTracker.hasPendingStabilityWork().shouldBeFalse()
        StableCompletePartialPolicy.shouldObservePartial("").shouldBeFalse()
    }

    "PRE-SPEECH 5. empty partial does nothing" {
        StableCompletePartialTracker.bind(1L)
        val empty = UnderstandingResult.unknown(sessionId = 1L, raw = "", normalized = "")
        StableCompletePartialTracker.onPartial(1L, empty, 0L, 1L).let {
            it.decision shouldBe StableCompletePartialTracker.Decision.IGNORE
            it.reason shouldBe "empty_transcript"
        }
        StableCompletePartialTracker.hasPendingStabilityWork().shouldBeFalse()
        SessionCommandDecision.bindSession(1L)
        SessionCommandDecision.locked(1L).shouldBeNull()
    }

    "PRE-SPEECH 6. empty final follows 4.2: no fast commit" {
        SpeechRecognizerSessionPolicy.onResults(
            utteranceCount = 0,
            generationMatches = true,
            sawSpeechOrPartial = false,
            elapsedMs = 80L,
            productTimeoutMs = productTimeout(),
        ) shouldBe SpeechRecognizerSessionPolicy.ProductAction.KEEP_PRODUCT_SESSION
        StableCompletePartialTracker.bind(1L)
        StableCompletePartialTracker.onTimer(1L, 80L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
    }

    "PRE-SPEECH 7. transient SR error follows 4.2 absorb" {
        SpeechRecognizerSessionPolicy.onError(
            code = SpeechRecognizerSessionPolicy.ERROR_CLIENT,
            generationMatches = true,
            sawReady = false,
            sawSpeechOrPartial = false,
            elapsedMs = 15L,
            productTimeoutMs = productTimeout(),
        ) shouldBe SpeechRecognizerSessionPolicy.ProductAction.KEEP_PRODUCT_SESSION
        SpeechRecognizerSessionPolicy.onError(
            code = SpeechRecognizerSessionPolicy.ERROR_SPEECH_TIMEOUT,
            generationMatches = true,
            sawReady = true,
            sawSpeechOrPartial = false,
            elapsedMs = 40L,
            productTimeoutMs = productTimeout(),
        ) shouldBe SpeechRecognizerSessionPolicy.ProductAction.KEEP_PRODUCT_SESSION
    }

    "PRE-SPEECH 8. only ~5s product timeout is the automatic pre-speech terminal" {
        VoiceSessionManager.NO_SPEECH_TIMEOUT_MS shouldBe 5_000L
        SpeechRecognizerSessionPolicy.onError(
            code = SpeechRecognizerSessionPolicy.ERROR_NO_MATCH,
            generationMatches = true,
            sawReady = true,
            sawSpeechOrPartial = false,
            elapsedMs = 4_999L,
            productTimeoutMs = productTimeout(),
        ) shouldBe SpeechRecognizerSessionPolicy.ProductAction.KEEP_PRODUCT_SESSION
        SpeechRecognizerSessionPolicy.onError(
            code = SpeechRecognizerSessionPolicy.ERROR_NO_MATCH,
            generationMatches = true,
            sawReady = true,
            sawSpeechOrPartial = false,
            elapsedMs = 5_000L,
            productTimeoutMs = productTimeout(),
        ) shouldBe SpeechRecognizerSessionPolicy.ProductAction.TERMINATE_NO_SPEECH
        StableCompletePartialPolicy.holdTimerMayCommit().shouldBeFalse()
    }

    "PARTIAL 9-12. incomplete phrases never early-execute" {
        StableCompletePartialTracker.bind(1L)
        listOf("chỉ đường đến", "mở bài", "mở bài đừng", "gọi").forEach { raw ->
            val obs = StableCompletePartialTracker.onPartial(1L, u(raw), 0L, 1L)
            obs.decision shouldBe StableCompletePartialTracker.Decision.IGNORE
            StableCompletePartialPolicy.isEligible(u(raw)).shouldBeFalse()
        }
        StableCompletePartialTracker.onEndOfSpeech(1L, 10L, 1L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
    }

    "PARTIAL 13. first COMPLETE Navigate waits for NAV stabilization without EOS" {
        StableCompletePartialTracker.bind(1L)
        val nav = u("Chỉ đường đến Mỹ Đình")
        val first = StableCompletePartialTracker.onPartial(1L, nav, 0L, 2L)
        first.decision shouldBe StableCompletePartialTracker.Decision.WAIT
        first.reason shouldBe "navigate_waiting_stable"
        StableCompletePartialTracker.onEndOfSpeech(1L, 5L, 2L).decision shouldBe
            StableCompletePartialTracker.Decision.WAIT
        StableCompletePartialTracker.onTimer(
            1L,
            StableCompletePartialPolicy.NAV_STABILIZATION_MS,
        ).decision shouldBe StableCompletePartialTracker.Decision.COMMIT
        StableCompletePartialTracker.onEndOfSpeech(1L, 800L, 2L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
    }

    "PARTIAL 14. later destination cannot replace a committed Navigate" {
        StableCompletePartialTracker.bind(1L)
        val a = u("Chỉ đường đến Mỹ Đình")
        val b = u("Chỉ đường đến Hồ Gươm")
        StableCompletePartialTracker.onPartial(1L, a, 0L, 2L).decision shouldBe
            StableCompletePartialTracker.Decision.WAIT
        StableCompletePartialTracker.onTimer(
            1L,
            NavigationCommitPolicy.noEosCommitAt(0L),
        ).decision shouldBe StableCompletePartialTracker.Decision.COMMIT
        StableCompletePartialTracker.onPartial(1L, a, 10L, 2L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
        StableCompletePartialTracker.onPartial(1L, b, 20L, 2L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
        StableCompletePartialPolicy.fingerprint(b) shouldBe "NAVIGATE|Hồ Gươm"
    }

    "PARTIAL 15. old stability state cannot affect a new session" {
        StableCompletePartialTracker.bind(1L)
        val nav = u("Chỉ đường đến Mỹ Đình")
        StableCompletePartialTracker.onPartial(1L, nav, 0L, 5L)
        StableCompletePartialTracker.onPartial(1L, nav, 10L, 5L)
        StableCompletePartialTracker.bind(2L)
        StableCompletePartialTracker.hasPendingStabilityWork().shouldBeFalse()
        StableCompletePartialTracker.onEndOfSpeech(1L, 20L, 5L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
        StableCompletePartialTracker.onTimer(1L, 350L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
        StableCompletePartialTracker.onEndOfSpeech(2L, 20L, 6L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
    }

    "PARTIAL 16. MODE cancel clears stability state" {
        StableCompletePartialTracker.bind(9L)
        val nav = u("Chỉ đường đến Mỹ Đình", 9L)
        StableCompletePartialTracker.onPartial(9L, nav, 0L, 3L)
        StableCompletePartialTracker.markCancelled()
        StableCompletePartialTracker.hasPendingStabilityWork().shouldBeFalse()
        StableCompletePartialTracker.onPartial(9L, nav, 10L, 3L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
        StableCompletePartialTracker.onEndOfSpeech(9L, 20L, 3L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
    }

    "PARTIAL 17. stale generation cannot commit" {
        StableCompletePartialTracker.bind(1L)
        val nav = u("Chỉ đường đến Mỹ Đình")
        StableCompletePartialTracker.onPartial(1L, nav, 0L, 8L)
        StableCompletePartialTracker.onPartial(1L, nav, 10L, 8L)
        StableCompletePartialTracker.onEndOfSpeech(1L, 20L, 7L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
        StableCompletePartialTracker.onPartial(1L, nav, 30L, 9L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
    }

    "PARTIAL 18-19. late final/error after commit cannot execute twice" {
        SessionCommandDecision.bindSession(1L)
        CanonicalActionGate.bind(1L)
        CommandSessionOutcome.claim(CommandSessionOutcome.Kind.EXECUTED).shouldBeTrue()
        CanonicalActionGate.tryClaim(1L).shouldBeTrue()
        SessionCommandDecision.decideFinal(1L, listOf("Chỉ đường đến Mỹ Đình" to 1f))
        CanonicalActionGate.tryClaim(1L).shouldBeFalse()
        CommandSessionOutcome.claim(CommandSessionOutcome.Kind.SR_ERROR).shouldBeFalse()
        SpeechRecognizerSessionPolicy.onError(
            code = SpeechRecognizerSessionPolicy.ERROR_CLIENT,
            generationMatches = false,
            sawReady = true,
            sawSpeechOrPartial = true,
            elapsedMs = 9_000L,
            productTimeoutMs = productTimeout(),
        ) shouldBe SpeechRecognizerSessionPolicy.ProductAction.IGNORE_STALE
    }

    "PARTIAL 20. ten sessions start with clean fast-path state" {
        repeat(10) { index ->
            val sid = (index + 1).toLong()
            StableCompletePartialTracker.bind(sid)
            SessionCommandDecision.bindSession(sid)
            CanonicalActionGate.bind(sid)
            CommandSessionOutcome.reset()
            StableCompletePartialTracker.hasPendingStabilityWork().shouldBeFalse()
            StableCompletePartialTracker.boundSessionIdForTests() shouldBe sid
            StableCompletePartialTracker.lastGenerationForTests() shouldBe 0L
            SessionCommandDecision.locked(sid).shouldBeNull()
            CommandSessionOutcome.peek() shouldBe CommandSessionOutcome.Kind.OPEN
            val empty = UnderstandingResult.unknown(sessionId = sid, raw = " ", normalized = "")
            // whitespace-only is observed as ineligible, not as a fingerprint
            StableCompletePartialTracker.onPartial(sid, empty, 0L, sid).decision shouldBe
                StableCompletePartialTracker.Decision.IGNORE
            StableCompletePartialTracker.hasPendingStabilityWork().shouldBeFalse()
        }
    }

    "ENTITY 21. chỉ đường đến Mỹ then Đình must not lock Mỹ" {
        val my = u("chỉ đường đến Mỹ")
        val full = u("chỉ đường đến Mỹ Đình")
        my.command shouldBe CanonicalCommand.Navigate("Mỹ")
        StableCompletePartialPolicy.navigateDestinationIsFastPathSafe("Mỹ").shouldBeFalse()
        StableCompletePartialPolicy.navigateDestinationIsFastPathSafe("Mỹ Đình").shouldBeTrue()
        StableCompletePartialTracker.bind(1L)
        StableCompletePartialTracker.onPartial(1L, my, 0L, 1L).decision shouldBe
            StableCompletePartialTracker.Decision.WAIT
        StableCompletePartialTracker.onPartial(1L, my, 10L, 1L).decision shouldBe
            StableCompletePartialTracker.Decision.WAIT
        StableCompletePartialTracker.onEndOfSpeech(1L, 400L, 1L).decision shouldBe
            StableCompletePartialTracker.Decision.WAIT
        val grown = StableCompletePartialTracker.onPartial(1L, full, 500L, 1L)
        grown.decision shouldBe StableCompletePartialTracker.Decision.WAIT
        grown.fingerprint shouldBe "NAVIGATE|Mỹ Đình"
        SessionCommandDecision.bindSession(1L)
        SessionCommandDecision.locked(1L).shouldBeNull()
    }

    "ENTITY 22. Navigate complete destination commits after NAV stabilization without EOS" {
        StableCompletePartialTracker.bind(1L)
        val result = u("Chỉ đường đến Mỹ Đình")
        result.command shouldBe CanonicalCommand.Navigate("Mỹ Đình")
        StableCompletePartialPolicy.isEligible(result).shouldBeTrue()
        StableCompletePartialPolicy.isSemanticEarlyCommitSafe(result).shouldBeTrue()
        val obs = StableCompletePartialTracker.onPartial(1L, result, 0L, 1L)
        obs.decision shouldBe StableCompletePartialTracker.Decision.WAIT
        obs.reason shouldBe "navigate_waiting_stable"
        obs.eosConfirmed.shouldBeFalse()
        StableCompletePartialTracker.onTimer(
            1L,
            StableCompletePartialPolicy.NAV_STABILIZATION_MS,
        ).decision shouldBe StableCompletePartialTracker.Decision.WAIT
        val commit = StableCompletePartialTracker.onTimer(
            1L,
            NavigationCommitPolicy.noEosCommitAt(0L),
        )
        commit.decision shouldBe StableCompletePartialTracker.Decision.COMMIT
        commit.reason shouldBe "semantic_navigate_stable"
        commit.eosConfirmed.shouldBeFalse()
        NavigationCommitPolicy.noEosCommitAt(0L) shouldBe 1_500L
    }

    "ENTITY 23-24. OpenApp/PlayMedia semantic-commit on first complete partial" {
        data class Case(val sid: Long, val raw: String, val command: CanonicalCommand)
        listOf(
            Case(2L, "Mở YouTube", CanonicalCommand.OpenApp("YouTube")),
            Case(
                3L,
                "Mở bài Đừng xa em đêm nay trên YouTube",
                CanonicalCommand.PlayMedia("Đừng xa em đêm nay", "YouTube"),
            ),
        ).forEach { case ->
            StableCompletePartialTracker.bind(case.sid)
            val result = u(case.raw, case.sid)
            result.command shouldBe case.command
            StableCompletePartialPolicy.isEligible(result).shouldBeTrue()
            StableCompletePartialPolicy.isSemanticEarlyCommitSafe(result).shouldBeTrue()
            val first = StableCompletePartialTracker.onPartial(case.sid, result, 0L, case.sid)
            first.decision shouldBe StableCompletePartialTracker.Decision.COMMIT
            first.eosConfirmed.shouldBeFalse()
        }
    }

    "ENTITY 25. CALL remains excluded from fast path" {
        val call = VietnameseCommandUnderstanding.understand("gọi Nam", 4L)
        StableCompletePartialPolicy.isEligible(call).shouldBeFalse()
        StableCompletePartialTracker.bind(4L)
        StableCompletePartialTracker.onPartial(4L, call, 0L, 4L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
        StableCompletePartialTracker.onEndOfSpeech(4L, 20L, 4L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
    }

    "PHASE 4.2 26-31. cancel/silence/rearm/exactly-one unchanged" {
        VoiceSessionManager.NO_SPEECH_TIMEOUT_MS shouldBe 5_000L
        CommandRecognitionPolicy.MAX_SR_REARMS shouldBe 0
        CommandRecognitionPolicy.shouldRearmSpeechRecognizer(0).shouldBeFalse()
        SpeechRecognizerSessionPolicy.endOfSpeechTerminatesProduct().shouldBeFalse()
        KnownGoodListenerInvariants.startListeningCountForModePress() shouldBe 1
        KnownGoodListenerInvariants.partialMayTerminateListener().shouldBeFalse()
        KnownGoodListenerInvariants.fastPartialRuntimeExecutionEnabled().shouldBeFalse()
        KnownGoodListenerInvariants.preSpeechFastPathMayArmTimer().shouldBeFalse()
        CommandSessionOutcome.reset()
        CommandSessionOutcome.claim(CommandSessionOutcome.Kind.EXECUTED).shouldBeTrue()
        CommandSessionOutcome.claim(CommandSessionOutcome.Kind.EXECUTED).shouldBeFalse()
        SpeechRecognizerSessionPolicy.onError(
            code = SpeechRecognizerSessionPolicy.ERROR_NO_MATCH,
            generationMatches = true,
            sawReady = false,
            sawSpeechOrPartial = false,
            elapsedMs = 10L,
            productTimeoutMs = productTimeout(),
        ) shouldBe SpeechRecognizerSessionPolicy.ProductAction.KEEP_PRODUCT_SESSION
    }

    "SessionCommandDecision stays OPEN / unlocked before real speech" {
        SessionCommandDecision.bindSession(44L)
        SessionCommandDecision.locked(44L).shouldBeNull()
        SessionCommandDecision.provisional(44L).shouldBeNull()
        SessionCommandDecision.isCancelled(44L).shouldBeFalse()
        CommandSessionOutcome.peek() shouldBe CommandSessionOutcome.Kind.OPEN
    }

    "EOS without eligible partial does not commit or lock" {
        StableCompletePartialTracker.bind(1L)
        StableCompletePartialTracker.onEndOfSpeech(1L, 5L, 1L).let {
            it.decision shouldBe StableCompletePartialTracker.Decision.IGNORE
            it.reason shouldBe "eos_without_eligible_partial"
        }
        StableCompletePartialTracker.hasPendingStabilityWork().shouldBeFalse()
    }

    "blank-text SR partials are dropped before product" {
        SpeechRecognizerSessionPolicy.onPartial(
            generationMatches = true,
            textBlank = true,
        ).shouldBeFalse()
        SpeechRecognizerSessionPolicy.onPartial(
            generationMatches = true,
            textBlank = false,
        ).shouldBeTrue()
    }
})
