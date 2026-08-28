package org.stypox.dicio.ui.driving

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class DrivingQuickActionsTest : StringSpec({
    "15 Nhạc tile launches MusicLoop first" {
        val pkg = DrivingQuickActions.resolveMusicPackage { candidate ->
            candidate == "com.musicloop.car"
        }
        pkg shouldBe "com.musicloop.car"
    }

    "16 Nhạc falls back to com.syu.music" {
        val pkg = DrivingQuickActions.resolveMusicPackage { candidate ->
            candidate == "com.syu.music"
        }
        pkg shouldBe "com.syu.music"
        DrivingQuickActions.resolveMusicPackage { false } shouldBe null
        DrivingQuickActions.MUSIC_PACKAGES shouldContainExactly listOf(
            "com.musicloop.car",
            "com.syu.music",
        )
    }

    "17 Âm lượng tile invokes its in-app controller" {
        DrivingQuickActions.volumeTileAction() shouldBe
            VolumeTileAction.SHOW_IN_APP_CONTROLLER
        val volume = intArrayOf(7)
        val muted = booleanArrayOf(false)
        val controller = DrivingVolumeController(
            getVolume = { volume[0] },
            getMax = { 15 },
            isMuted = { muted[0] },
            setVolume = { volume[0] = it },
            setMuted = { muted[0] = it },
        )
        controller.quieter().shouldBeInstanceOf<VolumeOpResult.Ok>()
        volume[0] shouldBe 6
        controller.louder().shouldBeInstanceOf<VolumeOpResult.Ok>()
        volume[0] shouldBe 7
        controller.toggleMute().shouldBeInstanceOf<VolumeOpResult.Ok>()
        muted[0] shouldBe true
        DrivingVolumePolicy.MIN_TOUCH_TARGET_DP shouldBe 80
        DrivingVolumePolicy.AUTO_DISMISS_IDLE_MS shouldBe 4_000L
    }

    "18 all five tile containers are enabled and clickable" {
        val tiles = DrivingQuickActions.allTilesClickable()
        tiles.map { it.id } shouldContainExactly listOf(
            "nav", "music", "call", "apps", "volume",
        )
        tiles.forEach { tile ->
            tile.containerClickable shouldBe true
            tile.enabled shouldBe true
            tile.minHeightDp shouldBe DrivingQuickActions.TILE_HEIGHT_DP
            tile.minTouchDp shouldBe DrivingQuickActions.MIN_TOUCH_DP
            tile.overlayConsumesTouches shouldBe false
        }
    }
})
