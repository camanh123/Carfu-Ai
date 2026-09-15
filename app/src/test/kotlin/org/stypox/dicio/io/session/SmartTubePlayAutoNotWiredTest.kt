package org.stypox.dicio.io.session

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Phase 1 SmartTube PlayAuto is standalone. Production Voice stays unwired.
 * Additive freeze check — does not change NLU, MediaProviderExecutor, or YouTube.
 */
class SmartTubePlayAutoNotWiredTest : StringSpec({
    beforeTest {
        StableCompletePartialTracker.resetForTests()
        SessionCommandDecision.resetForTests()
        CanonicalActionGate.resetForTests()
        CommandSessionOutcome.resetForTests()
        MediaProviderExecutor.resetForTests()
    }

    val song = "Đừng Xa Em Đêm Nay"

    fun u(raw: String) = VietnameseCommandUnderstanding.understand(raw, sessionId = 1L)

    "existing Media grammar already labels PlayMedia SmartTube" {
        u("Mở bài Đừng Xa Em Đêm Nay trên SmartTube").command shouldBe
            CanonicalCommand.PlayMedia(song, "SmartTube")
        u("Phát Nơi Này Có Anh bằng SmartTube").command shouldBe
            CanonicalCommand.PlayMedia("Nơi Này Có Anh", "SmartTube")
        u("Mở See You Again trên SmartTube").command shouldBe
            CanonicalCommand.PlayMedia("See You Again", "SmartTube")
    }

    "production executor does not wire SmartTube PlayAuto" {
        val built = MediaProviderExecutor.build(song, "SmartTube")
        built.shouldBeInstanceOf<MediaProviderExecutor.Result.Unsupported>()
        built.reason shouldContain "unknown_provider"
    }

    "YouTube PlayMedia remains the frozen YouTube PlayAuto route" {
        MediaProviderExecutor.build(song, "YouTube")
            .shouldBeInstanceOf<MediaProviderExecutor.Result.YouTubePlayAuto>()
            .query shouldBe song
    }
})
