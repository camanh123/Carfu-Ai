package org.stypox.dicio.io.session

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Phase 4.5 NLU already labels SmartTube PlayMedia. Production now jacks that
 * command onto SmartTube PlayAuto without changing YouTube routing.
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

    "production executor routes SmartTube to the SmartTube PlayAuto jack" {
        MediaProviderExecutor.build(song, "SmartTube")
            .shouldBeInstanceOf<MediaProviderExecutor.Result.SmartTubePlayAuto>()
            .query shouldBe song
    }

    "YouTube PlayMedia remains the frozen YouTube PlayAuto route" {
        MediaProviderExecutor.build(song, "YouTube")
            .shouldBeInstanceOf<MediaProviderExecutor.Result.YouTubePlayAuto>()
            .query shouldBe song
    }
})
