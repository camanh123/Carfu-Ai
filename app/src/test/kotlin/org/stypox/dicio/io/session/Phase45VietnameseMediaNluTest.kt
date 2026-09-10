package org.stypox.dicio.io.session

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Phase 4.5 — compositional Vietnamese PLAY_MEDIA / OPEN_APP NLU.
 * Does not change Phase 4.4 semantic early-commit timing rules.
 */
class Phase45VietnameseMediaNluTest : StringSpec({
    beforeTest {
        StableCompletePartialTracker.resetForTests()
        SessionCommandDecision.resetForTests()
        CanonicalActionGate.resetForTests()
        CommandSessionOutcome.resetForTests()
    }

    fun u(raw: String, sid: Long = 1L) =
        VietnameseCommandUnderstanding.understand(raw, sessionId = sid)

    val expectedMedia = CanonicalCommand.PlayMedia("Đừng Xa Em Đêm Nay", "YouTube")

    fun assertPlayMedia(raw: String) {
        val result = u(raw)
        result.intent shouldBe VoiceIntent.PLAY_MEDIA
        result.completeness shouldBe SemanticCompleteness.COMPLETE
        result.executable.shouldBeTrue()
        result.command shouldBe expectedMedia
        result.query shouldBe "Đừng Xa Em Đêm Nay"
        result.provider shouldBe "YouTube"
        result.rawTranscript shouldBe raw
        VietnameseCommandUnderstanding.confirmationSpeechVi(result.command!!) shouldBe
            "Đang mở Đừng Xa Em Đêm Nay trên YouTube"
    }

    "OPEN_APP Mở YouTube" {
        val result = u("Mở YouTube")
        result.command shouldBe CanonicalCommand.OpenApp("YouTube")
        result.intent shouldBe VoiceIntent.OPEN_APP
        StableCompletePartialPolicy.isSemanticEarlyCommitSafe(result).shouldBeTrue()
    }

    "OPEN_APP Mở Chrome" {
        u("Mở Chrome").command shouldBe CanonicalCommand.OpenApp("Chrome")
    }

    "PLAY_MEDIA Mở bài Đừng Xa Em Đêm Nay trên YouTube" {
        assertPlayMedia("Mở bài Đừng Xa Em Đêm Nay trên YouTube")
    }

    "PLAY_MEDIA Mở bài hát Đừng Xa Em Đêm Nay trên YouTube strips hat from query" {
        assertPlayMedia("Mở bài hát Đừng Xa Em Đêm Nay trên YouTube")
        u("Mở bài hát Đừng Xa Em Đêm Nay trên YouTube").query shouldBe "Đừng Xa Em Đêm Nay"
    }

    "PLAY_MEDIA Mở Đừng Xa Em Đêm Nay trên YouTube outranks OPEN_APP" {
        assertPlayMedia("Mở Đừng Xa Em Đêm Nay trên YouTube")
        u("Mở Đừng Xa Em Đêm Nay trên YouTube").command.shouldBe(expectedMedia)
        u("Mở Đừng Xa Em Đêm Nay trên YouTube")
            .command shouldBe CanonicalCommand.PlayMedia("Đừng Xa Em Đêm Nay", "YouTube")
    }

    "PLAY_MEDIA Phát Đừng Xa Em Đêm Nay trên YouTube" {
        assertPlayMedia("Phát Đừng Xa Em Đêm Nay trên YouTube")
    }

    "PLAY_MEDIA Cho tôi nghe Đừng Xa Em Đêm Nay trên YouTube" {
        assertPlayMedia("Cho tôi nghe Đừng Xa Em Đêm Nay trên YouTube")
    }

    "PLAY_MEDIA Bật bài Đừng Xa Em Đêm Nay bằng YouTube" {
        assertPlayMedia("Bật bài Đừng Xa Em Đêm Nay bằng YouTube")
    }

    "ELLIPTICAL bài hát Đừng Xa Em Đêm Nay trên YouTube" {
        val result = u("bài hát Đừng Xa Em Đêm Nay trên YouTube")
        result.command shouldBe expectedMedia
        result.query shouldBe "Đừng Xa Em Đêm Nay"
    }

    "ELLIPTICAL Đừng Xa Em Đêm Nay trên YouTube is provider-explicit PLAY_MEDIA" {
        assertPlayMedia("Đừng Xa Em Đêm Nay trên YouTube")
    }

    "PRIORITY Mở YouTube stays OPEN_APP while content+provider is PLAY_MEDIA" {
        u("Mở YouTube").intent shouldBe VoiceIntent.OPEN_APP
        u("Mở Đừng Xa Em Đêm Nay trên YouTube").intent shouldBe VoiceIntent.PLAY_MEDIA
    }

    "INCOMPLETE media commands are not executable" {
        listOf(
            "Mở",
            "Mở bài",
            "Mở bài hát",
            "Phát",
            "Cho tôi nghe",
            "Mở bài Đừng",
            "Mở trên YouTube",
            "Mở bài trên YouTube",
        ).forEach { raw ->
            val result = u(raw)
            result.completeness shouldBe SemanticCompleteness.INCOMPLETE
            result.executable.shouldBeFalse()
            result.command.shouldBeNull()
            StableCompletePartialPolicy.isSemanticEarlyCommitSafe(result).shouldBeFalse()
            StableCompletePartialTracker.resetForTests()
            StableCompletePartialTracker.bind(1L)
            StableCompletePartialTracker.onPartial(1L, result, 0L, 1L).decision shouldBe
                StableCompletePartialTracker.Decision.IGNORE
        }
    }

    "complete PLAY_MEDIA still semantic-commits exactly once (Phase 4.4 preserved)" {
        val media = u("Mở Đừng Xa Em Đêm Nay trên YouTube")
        StableCompletePartialPolicy.isSemanticEarlyCommitSafe(media).shouldBeTrue()
        StableCompletePartialTracker.bind(4L)
        val obs = StableCompletePartialTracker.onPartial(4L, media, 0L, 4L)
        obs.decision shouldBe StableCompletePartialTracker.Decision.COMMIT
        obs.reason shouldBe "semantic_play_media"
        SessionCommandDecision.bindSession(4L)
        CanonicalActionGate.bind(4L)
        SessionCommandDecision.lockFinal(4L, media.copy(executable = true))!!.command shouldBe
            expectedMedia
        CanonicalActionGate.tryClaim(4L).shouldBeTrue()
        CommandSessionOutcome.claim(CommandSessionOutcome.Kind.EXECUTED).shouldBeTrue()
        CanonicalActionGate.tryClaim(4L).shouldBeFalse()
        CommandSessionOutcome.claim(CommandSessionOutcome.Kind.EXECUTED).shouldBeFalse()
        StableCompletePartialTracker.onPartial(4L, media, 20L, 4L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
    }

    "legacy Mở bài lowercase query still maps to PLAY_MEDIA" {
        val result = u("Mở bài Đừng xa em đêm nay trên YouTube")
        result.command shouldBe CanonicalCommand.PlayMedia("Đừng xa em đêm nay", "YouTube")
        StableCompletePartialPolicy.isSemanticEarlyCommitSafe(result).shouldBeTrue()
    }
})
