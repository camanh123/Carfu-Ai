package org.stypox.dicio.io.input

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.shouldBe
import org.stypox.dicio.io.session.CanonicalCommand
import org.stypox.dicio.io.session.CarfuDiag
import org.stypox.dicio.io.session.MediaProviderExecutor
import org.stypox.dicio.io.session.NavigatePayload
import org.stypox.dicio.io.session.VietnameseCommandUnderstanding
import org.stypox.dicio.io.session.VietnameseMediaCommandGrammar
import org.stypox.dicio.io.wake.BackgroundWakePolicy
import org.stypox.dicio.io.wake.oww.OpenWakeWordDevice

class ColdModeIsolationTest : StringSpec({
    beforeEach {
        OpenWakeWordDevice.resetConstructionCountForTests()
        CarfuDiag.clear()
    }

    "production MODE ASR is the pre-A/B VIA RecognizerIntent" {
        ColdModeIsolation.PRE_AB_REF_COMMIT shouldBe
            "7bdb5be65cae9b2ff4044637e2064fc35331e9a6"
        val extras = ColdModeIsolation.productionRecognizerExtras("org.stypox.dicio")
        extras shouldBe AsrRecognizerIntentProfiles.specFor(
            AsrTestProfile.A_VIA_CURRENT,
            "org.stypox.dicio",
        ).extras
        val cfg = CommandRecognitionPolicy.recognizerIntentConfig()
        cfg.action shouldBe "android.speech.action.RECOGNIZE_SPEECH"
        cfg.languageModel shouldBe "free_form"
        cfg.language shouldBe "vi-VN"
        cfg.partialResults shouldBe true
        cfg.maxResults shouldBe 3
        cfg.preferOffline shouldBe false
        CommandRecognitionPolicy.isAndroidOnline(
            CommandRecognitionPolicy.resolveEngine(
                org.stypox.dicio.settings.datastore.CommandRecognitionEngine
                    .COMMAND_RECOGNITION_ENGINE_UNSET,
            ),
        ) shouldBe true
        CommandRecognitionPolicy.shouldConstructVosk(
            org.stypox.dicio.settings.datastore.CommandRecognitionEngine
                .COMMAND_RECOGNITION_ENGINE_ANDROID_ONLINE,
        ).shouldBeFalse()
    }

    "production MODE path does not consult the A/B profile selector" {
        ColdModeIsolation.productionConsultsAsrProfileSelector().shouldBeFalse()
        ColdModeIsolation.ASR_AB_PRODUCTION shouldBe "UNPLUGGED"
        AsrTestProfileController::class.java.name shouldBe
            "org.stypox.dicio.io.input.AsrTestProfileController"
    }

    "OpenWakeWord is not constructed on the MODE isolation path" {
        ColdModeIsolation.OPENWAKEWORD_PRODUCTION shouldBe "UNPLUGGED"
        BackgroundWakePolicy.modeOnlyForceBackgroundWakeOff() shouldBe true
        ColdModeIsolation.shouldConstructOpenWakeWord().shouldBeFalse()
        OpenWakeWordDevice.constructionCount() shouldBe 0
    }

    "SmartTube provider aliases still parse" {
        VietnameseMediaCommandGrammar.resolveProviderLabel("smarttube") shouldBe "SmartTube"
        VietnameseMediaCommandGrammar.resolveProviderLabel("smart tube") shouldBe "SmartTube"
        val play = VietnameseCommandUnderstanding.understand(
            "Mở bài Đừng Xa Em Đêm Nay trên SmartTube",
        )
        play.command shouldBe CanonicalCommand.PlayMedia("Đừng Xa Em Đêm Nay", "SmartTube")
        val yt = VietnameseCommandUnderstanding.understand(
            "Mở bài See You Again trên YouTube",
        )
        yt.command shouldBe CanonicalCommand.PlayMedia("See You Again", "YouTube")
    }

    "YouTube SmartTube Maps execution routing is unchanged" {
        MediaProviderExecutor.build("See You Again", "YouTube") shouldBe
            MediaProviderExecutor.Result.YouTubePlayAuto("See You Again")
        MediaProviderExecutor.isSmartTubeProvider("SmartTube") shouldBe true
        MediaProviderExecutor.build("Đừng Xa Em Đêm Nay", "SmartTube") shouldBe
            MediaProviderExecutor.Result.SmartTubePlayAuto("Đừng Xa Em Đêm Nay")
        NavigatePayload.navigationUri("Mỹ Đình") shouldBe
            "google.navigation:q=M%E1%BB%B9%20%C4%90%C3%ACnh"
        NavigatePayload.GOOGLE_MAPS_PACKAGE shouldBe "com.google.android.apps.maps"
        val open = VietnameseCommandUnderstanding.understand("Chỉ đường đến Mỹ Đình")
        open.command shouldBe CanonicalCommand.Navigate("Mỹ Đình")
    }

    "copyable diagnostics include isolation flags" {
        val log = CarfuDiag.copyableLog()
        log.contains("ASR_AB_PRODUCTION=UNPLUGGED") shouldBe true
        log.contains("OPENWAKEWORD_PRODUCTION=UNPLUGGED") shouldBe true
        log.contains("ASR_PROFILE_SELECTOR_READ_ON_MODE=false") shouldBe true
    }
})
