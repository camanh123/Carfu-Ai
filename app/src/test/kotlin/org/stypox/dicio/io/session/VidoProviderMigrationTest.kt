package org.stypox.dicio.io.session

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.stypox.dicio.smarttubeplayauto.SmartTubeProductionPolicy

/**
 * VIDO is the user-facing name for the existing SmartTube production provider.
 * Canonical id, executor, and package stay SmartTube / org.smarttube.stable.
 */
class VidoProviderMigrationTest : StringSpec({
    fun u(raw: String) = VietnameseCommandUnderstanding.understand(raw)

    "mở Vido is OpenApp on the SmartTube production app" {
        val result = u("mở Vido")
        result.command shouldBe CanonicalCommand.OpenApp("SmartTube")
        VietnameseCommandUnderstanding.toExecutableRoutedCommand(result.command!!)
            ?.intent shouldBe CarfuIntent.OPEN_SMARTTUBE
        CarfuCommandRouter.match("mở Vido")!!.intent shouldBe CarfuIntent.OPEN_SMARTTUBE
        VietnameseCommandUnderstanding.confirmationSpeechVi(result.command!!) shouldBe "Đang mở VIDO"
    }

    "mở vi đô and mở vi do map to VIDO OpenApp" {
        u("mở vi đô").command shouldBe CanonicalCommand.OpenApp("SmartTube")
        u("mở vi do").command shouldBe CanonicalCommand.OpenApp("SmartTube")
        CarfuCommandRouter.match("mở vi đô")!!.intent shouldBe CarfuIntent.OPEN_SMARTTUBE
        CarfuCommandRouter.match("mở vi do")!!.intent shouldBe CarfuIntent.OPEN_SMARTTUBE
        VietnameseMediaCommandGrammar.resolveProviderLabel("vido") shouldBe "SmartTube"
        VietnameseMediaCommandGrammar.resolveProviderLabel("vi do") shouldBe "SmartTube"
    }

    "mở Vido lên and mở Vido cho tôi stay OPEN_SMARTTUBE" {
        CarfuCommandRouter.match("mở Vido lên")!!.intent shouldBe CarfuIntent.OPEN_SMARTTUBE
        CarfuCommandRouter.match("mở Vido cho tôi")!!.intent shouldBe CarfuIntent.OPEN_SMARTTUBE
    }

    "mở bài đừng xa em đêm nay trên Vido is PlayMedia SmartTube" {
        val result = u("mở bài đừng xa em đêm nay trên Vido")
        result.command shouldBe CanonicalCommand.PlayMedia("đừng xa em đêm nay", "SmartTube")
        result.query shouldBe "đừng xa em đêm nay"
        result.provider shouldBe "SmartTube"
        MediaProviderExecutor.build(result.query!!, result.provider)
            .shouldBeInstanceOf<MediaProviderExecutor.Result.SmartTubePlayAuto>()
        VietnameseCommandUnderstanding.confirmationSpeechVi(result.command!!) shouldBe "Đang mở đừng xa em đêm nay trên VIDO"
        MediaProviderExecutor.isSmartTubeProvider("VIDO") shouldBe true
        SmartTubeProductionPolicy.PACKAGE shouldBe "org.smarttube.stable"
    }

    "phát nơi này có anh bằng Vido is PlayMedia SmartTube" {
        val result = u("phát nơi này có anh bằng Vido")
        result.command shouldBe CanonicalCommand.PlayMedia("nơi này có anh", "SmartTube")
        MediaProviderExecutor.build("nơi này có anh", "SmartTube")
            .shouldBeInstanceOf<MediaProviderExecutor.Result.SmartTubePlayAuto>()
        VietnameseCommandUnderstanding.confirmationSpeechVi(result.command!!) shouldBe "Đang mở nơi này có anh trên VIDO"
    }

    "tìm sơn tùng trên Vido is PlayMedia search on SmartTube" {
        val result = u("tìm sơn tùng trên Vido")
        result.command shouldBe CanonicalCommand.PlayMedia("sơn tùng", "SmartTube")
        MediaProviderExecutor.isYouTubeProvider(result.provider) shouldBe false
        MediaProviderExecutor.build("sơn tùng", result.provider)
            .shouldBeInstanceOf<MediaProviderExecutor.Result.SmartTubePlayAuto>()
    }

    "Vido mở bài Nơi này có anh is PlayMedia SmartTube" {
        val result = u("Vido mở bài Nơi này có anh")
        result.command shouldBe CanonicalCommand.PlayMedia("Nơi này có anh", "SmartTube")
        VietnameseCommandUnderstanding.confirmationSpeechVi(result.command!!) shouldBe "Đang mở Nơi này có anh trên VIDO"
    }

    "legacy SmartTube PlayMedia still parses and executes the same jack" {
        val result = u("mở bài đừng xa em đêm nay trên SmartTube")
        result.command shouldBe CanonicalCommand.PlayMedia("đừng xa em đêm nay", "SmartTube")
        MediaProviderExecutor.build(result.query!!, "SmartTube")
            .shouldBeInstanceOf<MediaProviderExecutor.Result.SmartTubePlayAuto>()
        VietnameseMediaCommandGrammar.resolveProviderLabel("smarttube") shouldBe "SmartTube"
        VietnameseMediaCommandGrammar.resolveProviderLabel("smart tube") shouldBe "SmartTube"
        VietnameseCommandUnderstanding.confirmationSpeechVi(result.command!!) shouldBe "Đang mở đừng xa em đêm nay trên VIDO"
    }

    "YouTube PlayMedia is unchanged" {
        val result = u("mở bài Đừng xa em đêm nay trên YouTube")
        result.command shouldBe CanonicalCommand.PlayMedia("Đừng xa em đêm nay", "YouTube")
        MediaProviderExecutor.build(result.query!!, "YouTube")
            .shouldBeInstanceOf<MediaProviderExecutor.Result.YouTubePlayAuto>()
        VietnameseCommandUnderstanding.confirmationSpeechVi(result.command!!) shouldBe "Đang mở Đừng xa em đêm nay trên YouTube"
    }

    "Maps navigation is unchanged" {
        val result = u("Chỉ đường đến Mỹ Đình")
        result.command shouldBe CanonicalCommand.Navigate("Mỹ Đình")
        NavigatePayload.navigationUri("Mỹ Đình") shouldBe
            "google.navigation:q=M%E1%BB%B9%20%C4%90%C3%ACnh"
        NavigatePayload.GOOGLE_MAPS_PACKAGE shouldBe "com.google.android.apps.maps"
    }
})
