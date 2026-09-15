package org.stypox.dicio.smarttubeplayauto

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.stypox.dicio.youtubeplayauto.YouTubeVideoIdParser

class SmartTubeIntentBuilderTest : StringSpec({
    val videoId = "W20zl5N_jbg"
    val watch = YouTubeVideoIdParser.canonicalWatchUri(videoId)
    val target = ResolvedMediaTarget(
        videoId = videoId,
        resolvedTitle = "Đừng Xa Em Đêm Nay - Hồ Hoàng Yến [Official 4K MV]",
        watchUrl = watch,
    )
    val pin = SmartTubeCatalog.TEAMSMART

    "watch URL pinned requests the exact video on a catalog SmartTube package" {
        val spec = (SmartTubeIntentBuilder.build(
            SmartTubeLaunchForm.VIEW_WATCH_URL_PINNED,
            target,
            pin,
        ) as SmartTubeIntentBuild.Ok).spec
        spec.action shouldBe SmartTubeLaunchAudit.ACTION_VIEW
        spec.uri shouldBe watch
        spec.packageName shouldBe pin
        spec.exactVideoTargetRequested shouldBe true
        spec.videoId shouldBe videoId
        spec.resolvedTitle shouldBe target.resolvedTitle
    }

    "youtu.be and vnd.youtube candidates keep the same video id" {
        val youtu = (SmartTubeIntentBuilder.build(
            SmartTubeLaunchForm.VIEW_YOUTU_BE_PINNED,
            target,
            pin,
        ) as SmartTubeIntentBuild.Ok).spec
        youtu.uri shouldBe "https://youtu.be/$videoId"
        youtu.exactVideoTargetRequested shouldBe true
        val vnd = (SmartTubeIntentBuilder.build(
            SmartTubeLaunchForm.VIEW_VND_YOUTUBE_PINNED,
            target,
            pin,
        ) as SmartTubeIntentBuild.Ok).spec
        vnd.uri shouldBe "vnd.youtube:$videoId"
        vnd.exactVideoTargetRequested shouldBe true
    }

    "MAIN launcher is not an exact video target" {
        val spec = (SmartTubeIntentBuilder.build(
            SmartTubeLaunchForm.MAIN_LAUNCHER,
            target,
            pin,
        ) as SmartTubeIntentBuild.Ok).spec
        spec.action shouldBe SmartTubeLaunchAudit.ACTION_MAIN
        spec.uri shouldBe null
        spec.exactVideoTargetRequested shouldBe false
    }

    "invalid video id does not build an Intent" {
        val bad = ResolvedMediaTarget("not-valid", "x", "https://www.youtube.com/watch?v=not-valid")
        SmartTubeIntentBuilder.build(
            SmartTubeLaunchForm.VIEW_WATCH_URL_PINNED,
            bad,
            pin,
        ) shouldBe SmartTubeIntentBuild.Failed("invalid_video_id")
    }

    "catalog TEAMSMART is a catalog candidate, not device proof" {
        SmartTubeCatalog.PACKAGE_EVIDENCE shouldBe "catalog_only_not_device_proven"
        SmartTubePackageNames.isSafeSmartTubeTarget(SmartTubeCatalog.TEAMSMART) shouldBe true
        SmartTubePackageNames.isSafeSmartTubeTarget("com.google.android.youtube") shouldBe false
        SmartTubeLaunchAudit.SOURCE_PROVEN shouldBe false
    }
})
