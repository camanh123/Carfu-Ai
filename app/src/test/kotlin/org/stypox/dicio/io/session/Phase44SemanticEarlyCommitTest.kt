package org.stypox.dicio.io.session

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.stypox.dicio.io.input.CommandRecognitionPolicy
import org.stypox.dicio.io.input.SpeechRecognizerSessionPolicy

/**
 * Phase 4.4 matrix: semantic OPEN_APP / PLAY_MEDIA early commit without an
 * arbitrary timer, without requiring EOS, and without duplicate execution after
 * late partial / Final / Error / SR_HARD_CEILING / transcript_rescued.
 */
class Phase44SemanticEarlyCommitTest : StringSpec({
    beforeTest {
        StableCompletePartialTracker.resetForTests()
        SessionCommandDecision.resetForTests()
        CanonicalActionGate.resetForTests()
        CommandSessionOutcome.resetForTests()
        VoiceSessionManager.resetForTests()
    }

    fun u(raw: String, sid: Long = 1L) =
        VietnameseCommandUnderstanding.understand(raw, sessionId = sid)

    fun lockOnce(sid: Long, result: UnderstandingResult): UnderstandingResult {
        SessionCommandDecision.bindSession(sid)
        CanonicalActionGate.bind(sid)
        val locked = SessionCommandDecision.lockFinal(sid, result.copy(executable = true))!!
        CanonicalActionGate.tryClaim(sid).shouldBeTrue()
        CommandSessionOutcome.claim(CommandSessionOutcome.Kind.EXECUTED).shouldBeTrue()
        return locked
    }

    "OPEN_APP Mở alone does not execute" {
        val incomplete = u("Mở")
        incomplete.completeness shouldBe SemanticCompleteness.INCOMPLETE
        StableCompletePartialPolicy.isEligible(incomplete).shouldBeFalse()
        StableCompletePartialPolicy.isSemanticEarlyCommitSafe(incomplete).shouldBeFalse()
        StableCompletePartialTracker.bind(1L)
        StableCompletePartialTracker.onPartial(1L, incomplete, 0L, 1L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
        SessionCommandDecision.bindSession(1L)
        SessionCommandDecision.onPartial(1L, incomplete)
        SessionCommandDecision.locked(1L).shouldBeNull()
    }

    "OPEN_APP Mở YouTube early-commits exactly once" {
        val open = u("Mở YouTube")
        open.command shouldBe CanonicalCommand.OpenApp("YouTube")
        open.reason shouldBe "open_catalog"
        VietnameseCommandUnderstanding.isExactCatalogOpenApp(open).shouldBeTrue()
        StableCompletePartialTracker.bind(1L)
        val obs = StableCompletePartialTracker.onPartial(1L, open, 0L, 8L)
        obs.decision shouldBe StableCompletePartialTracker.Decision.COMMIT
        obs.reason shouldBe "semantic_open_app"
        lockOnce(1L, open).command shouldBe CanonicalCommand.OpenApp("YouTube")
        CanonicalActionGate.tryClaim(1L).shouldBeFalse()
        CommandSessionOutcome.claim(CommandSessionOutcome.Kind.EXECUTED).shouldBeFalse()
    }

    "OPEN_APP Mở Chrome early-commits exactly once" {
        val open = u("Mở Chrome")
        open.command shouldBe CanonicalCommand.OpenApp("Chrome")
        StableCompletePartialTracker.bind(2L)
        StableCompletePartialTracker.onPartial(2L, open, 0L, 3L).decision shouldBe
            StableCompletePartialTracker.Decision.COMMIT
        lockOnce(2L, open).command shouldBe CanonicalCommand.OpenApp("Chrome")
        CommandSessionOutcome.claim(CommandSessionOutcome.Kind.EXECUTED).shouldBeFalse()
    }

    "OPEN_APP Mở MusicLoop early-commits" {
        val open = u("Mở MusicLoop")
        open.command shouldBe CanonicalCommand.OpenApp("MusicLoop")
        StableCompletePartialPolicy.isSemanticEarlyCommitSafe(open).shouldBeTrue()
        StableCompletePartialTracker.bind(3L)
        StableCompletePartialTracker.onPartial(3L, open, 0L, 1L).reason shouldBe
            "semantic_open_app"
    }

    "OPEN_APP unknown app does not early-execute" {
        val unknown = u("Mở FooBarApp")
        unknown.command shouldBe CanonicalCommand.OpenApp("FooBarApp")
        unknown.reason shouldBe "open_generic"
        VietnameseCommandUnderstanding.isExactCatalogOpenApp(unknown).shouldBeFalse()
        StableCompletePartialPolicy.isSemanticEarlyCommitSafe(unknown).shouldBeFalse()
        StableCompletePartialTracker.bind(1L)
        // Generic OPEN_APP may be eligible for the conservative EOS path, but must WAIT.
        val obs = StableCompletePartialTracker.onPartial(1L, unknown, 0L, 1L)
        obs.decision shouldBe StableCompletePartialTracker.Decision.WAIT
        SessionCommandDecision.bindSession(1L)
        SessionCommandDecision.onPartial(1L, unknown)
        SessionCommandDecision.locked(1L).shouldBeNull()
    }

    "OPEN_APP ambiguous SmartTube does not early-execute" {
        val shortForm = u("Mở SmartTube")
        shortForm.command shouldBe CanonicalCommand.OpenApp("SmartTube")
        CommandTranscriptNormalizer.isPrefixAmbiguous(shortForm.normalizedTranscript).shouldBeTrue()
        StableCompletePartialPolicy.isOpenAppPrefixAmbiguous(shortForm).shouldBeTrue()
        StableCompletePartialPolicy.isSemanticEarlyCommitSafe(shortForm).shouldBeFalse()
        StableCompletePartialPolicy.isEligible(shortForm).shouldBeFalse()
        StableCompletePartialTracker.bind(1L)
        StableCompletePartialTracker.onPartial(1L, shortForm, 0L, 1L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
        SessionCommandDecision.bindSession(1L)
        SessionCommandDecision.locked(1L).shouldBeNull()
    }

    "OPEN_APP Mở bản đồ is prefix of Navigate and must not early-commit" {
        val maps = u("Mở bản đồ")
        maps.command shouldBe CanonicalCommand.OpenApp("Maps")
        CommandTranscriptNormalizer.isPrefixOfSupportedNavigation(maps.normalizedTranscript)
            .shouldBeTrue()
        StableCompletePartialPolicy.canExtendToHigherPriorityCanonical(maps).shouldBeTrue()
        StableCompletePartialPolicy.isSemanticEarlyCommitSafe(maps).shouldBeFalse()
        StableCompletePartialTracker.bind(1L)
        StableCompletePartialTracker.onPartial(1L, maps, 0L, 1L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
    }

    "OPEN_APP truncated You… is not exact catalog" {
        val truncated = u("Mở You")
        VietnameseCommandUnderstanding.isExactCatalogOpenApp(truncated).shouldBeFalse()
        StableCompletePartialPolicy.isSemanticEarlyCommitSafe(truncated).shouldBeFalse()
        StableCompletePartialTracker.bind(1L)
        val obs = StableCompletePartialTracker.onPartial(1L, truncated, 0L, 1L)
        obs.decision shouldBe StableCompletePartialTracker.Decision.IGNORE
        SessionCommandDecision.bindSession(1L)
        SessionCommandDecision.locked(1L).shouldBeNull()
    }

    "OPEN_APP Mở ứng dụng does not early-execute" {
        val generic = u("Mở ứng dụng")
        StableCompletePartialPolicy.isSemanticEarlyCommitSafe(generic).shouldBeFalse()
        StableCompletePartialTracker.bind(1L)
        StableCompletePartialTracker.onPartial(1L, generic, 0L, 1L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
    }

    "YouTube is not a prefix of a supported longer canonical command" {
        val open = u("Mở YouTube")
        CommandTranscriptNormalizer.isPrefixAmbiguous(open.normalizedTranscript).shouldBeFalse()
        CommandTranscriptNormalizer.isPrefixOfSupportedNavigation(open.normalizedTranscript)
            .shouldBeFalse()
        StableCompletePartialPolicy.canExtendToHigherPriorityCanonical(open).shouldBeFalse()
        // No provider preposition → still OPEN_APP, not PLAY_MEDIA.
        u("Mở YouTube và tìm bài hát").intent shouldBe VoiceIntent.OPEN_APP
    }

    "early commit then late partial is ignored" {
        val open = u("Mở YouTube")
        StableCompletePartialTracker.bind(1L)
        StableCompletePartialTracker.onPartial(1L, open, 0L, 4L).decision shouldBe
            StableCompletePartialTracker.Decision.COMMIT
        lockOnce(1L, open)
        StableCompletePartialTracker.onPartial(1L, open, 50L, 4L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
        StableCompletePartialTracker.onPartial(1L, u("Mở Chrome"), 60L, 4L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
        CanonicalActionGate.tryClaim(1L).shouldBeFalse()
    }

    "early commit then late Final is ignored" {
        val open = u("Mở YouTube")
        StableCompletePartialTracker.bind(1L)
        StableCompletePartialTracker.onPartial(1L, open, 0L, 4L)
        lockOnce(1L, open)
        val late = SessionCommandDecision.decideFinal(1L, listOf("Mở YouTube" to 1f))
        late!!.command shouldBe CanonicalCommand.OpenApp("YouTube")
        CanonicalActionGate.tryClaim(1L).shouldBeFalse()
        CommandSessionOutcome.peek() shouldBe CommandSessionOutcome.Kind.EXECUTED
    }

    "early commit then late Error cannot damage session" {
        val open = u("Mở YouTube")
        StableCompletePartialTracker.bind(1L)
        StableCompletePartialTracker.onPartial(1L, open, 0L, 4L)
        lockOnce(1L, open)
        SpeechRecognizerSessionPolicy.onError(
            code = SpeechRecognizerSessionPolicy.ERROR_CLIENT,
            generationMatches = false,
            sawReady = true,
            sawSpeechOrPartial = true,
            elapsedMs = 8_000L,
            productTimeoutMs = VoiceSessionManager.NO_SPEECH_TIMEOUT_MS,
        ) shouldBe SpeechRecognizerSessionPolicy.ProductAction.IGNORE_STALE
        CommandSessionOutcome.claim(CommandSessionOutcome.Kind.SR_ERROR).shouldBeFalse()
        CommandSessionOutcome.claim(CommandSessionOutcome.Kind.NO_SPEECH).shouldBeFalse()
    }

    "early commit then SR_HARD_CEILING cannot duplicate action" {
        CommandRecognitionPolicy.ANDROID_LISTEN_TIMEOUT_MS shouldBe 12_000L
        val open = u("Mở YouTube")
        StableCompletePartialTracker.bind(1L)
        StableCompletePartialTracker.onPartial(1L, open, 0L, 4L).decision shouldBe
            StableCompletePartialTracker.Decision.COMMIT
        lockOnce(1L, open)
        // Ceiling failsafe still exists, but outcome is no longer OPEN.
        CommandSessionOutcome.peek() shouldBe CommandSessionOutcome.Kind.EXECUTED
        SessionCommandDecision.locked(1L)!!.command shouldBe CanonicalCommand.OpenApp("YouTube")
        CanonicalActionGate.tryClaim(1L).shouldBeFalse()
        CommandSessionOutcome.claim(CommandSessionOutcome.Kind.EXECUTED).shouldBeFalse()
        StableCompletePartialTracker.onPartial(1L, open, 12_000L, 4L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
    }

    "early commit then transcript_rescued cannot duplicate action" {
        val open = u("Mở YouTube")
        StableCompletePartialTracker.bind(1L)
        StableCompletePartialTracker.onPartial(1L, open, 0L, 4L)
        lockOnce(1L, open)
        val rescued = SessionCommandDecision.decideFinal(
            1L,
            listOf("Mở YouTube" to 1f),
        )
        rescued!!.command shouldBe CanonicalCommand.OpenApp("YouTube")
        CanonicalActionGate.tryClaim(1L).shouldBeFalse()
        CommandSessionOutcome.peek() shouldBe CommandSessionOutcome.Kind.EXECUTED
    }

    "MODE cancel before lock prevents OPEN_APP execution" {
        val open = u("Mở YouTube", 7L)
        StableCompletePartialTracker.bind(7L)
        SessionCommandDecision.bindSession(7L)
        CanonicalActionGate.bind(7L)
        SessionCommandDecision.markCancelled(7L)
        CanonicalActionGate.markCancelled(7L)
        StableCompletePartialTracker.markCancelled()
        StableCompletePartialTracker.onPartial(7L, open, 0L, 2L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
        SessionCommandDecision.lockFinal(7L, open.copy(executable = true)).shouldBeNull()
        CanonicalActionGate.tryClaim(7L).shouldBeFalse()
    }

    "stale session and generation cannot semantic-commit" {
        val open = u("Mở YouTube")
        val generic = u("Mở FooBarApp")
        StableCompletePartialTracker.bind(1L)
        StableCompletePartialTracker.onPartial(99L, open, 0L, 1L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
        StableCompletePartialTracker.onPartial(1L, generic, 0L, 11L).decision shouldBe
            StableCompletePartialTracker.Decision.WAIT
        StableCompletePartialTracker.onPartial(1L, generic, 10L, 99L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
        StableCompletePartialTracker.onPartial(1L, open, 20L, 99L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
    }

    "PLAY_MEDIA complete query+provider early-commits" {
        val media = u("Mở bài Đừng xa em đêm nay trên YouTube")
        media.completeness shouldBe SemanticCompleteness.COMPLETE
        VietnameseCommandUnderstanding.isKnownPlayMediaProvider("YouTube").shouldBeTrue()
        StableCompletePartialPolicy.isSemanticEarlyCommitSafe(media).shouldBeTrue()
        StableCompletePartialTracker.bind(5L)
        val obs = StableCompletePartialTracker.onPartial(5L, media, 0L, 5L)
        obs.decision shouldBe StableCompletePartialTracker.Decision.COMMIT
        obs.reason shouldBe "semantic_play_media"
        lockOnce(5L, media)
        CommandSessionOutcome.claim(CommandSessionOutcome.Kind.EXECUTED).shouldBeFalse()
    }

    "PLAY_MEDIA incomplete query does not early-execute" {
        listOf(
            "Mở bài",
            "Mở bài Đừng",
            "Mở bài Đừng xa em",
            "Mở bài trên YouTube",
        ).forEach { raw ->
            val result = u(raw)
            result.completeness shouldBe SemanticCompleteness.INCOMPLETE
            StableCompletePartialPolicy.isSemanticEarlyCommitSafe(result).shouldBeFalse()
            StableCompletePartialTracker.bind(1L)
            StableCompletePartialTracker.onPartial(1L, result, 0L, 1L).decision shouldBe
                StableCompletePartialTracker.Decision.IGNORE
        }
    }

    "PLAY_MEDIA unknown provider does not early-execute" {
        val unknownProvider = u("Mở bài Đừng xa em đêm nay trên FooTube")
        unknownProvider.completeness shouldBe SemanticCompleteness.COMPLETE
        unknownProvider.command shouldBe CanonicalCommand.PlayMedia(
            "Đừng xa em đêm nay",
            "FooTube",
        )
        VietnameseCommandUnderstanding.isKnownPlayMediaProvider("FooTube").shouldBeFalse()
        StableCompletePartialPolicy.isSemanticEarlyCommitSafe(unknownProvider).shouldBeFalse()
        StableCompletePartialTracker.bind(1L)
        StableCompletePartialTracker.onPartial(1L, unknownProvider, 0L, 1L).decision shouldBe
            StableCompletePartialTracker.Decision.WAIT
    }

    "hard ceiling value and rearm policy unchanged" {
        CommandRecognitionPolicy.ANDROID_LISTEN_TIMEOUT_MS shouldBe 12_000L
        CommandRecognitionPolicy.MAX_SR_REARMS shouldBe 0
        StableCompletePartialPolicy.holdTimerMayCommit().shouldBeFalse()
        StableCompletePartialPolicy.STABILITY_MS shouldBe 0L
        StableCompletePartialPolicy.requiresEndOfSpeechForOpenApp().shouldBeFalse()
        VoiceSessionManager.NO_SPEECH_TIMEOUT_MS shouldBe 5_000L
    }
})
