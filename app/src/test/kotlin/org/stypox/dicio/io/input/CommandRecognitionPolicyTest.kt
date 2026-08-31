package org.stypox.dicio.io.input

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.stypox.dicio.settings.datastore.BackgroundWake
import org.stypox.dicio.settings.datastore.CommandRecognitionEngine
import org.stypox.dicio.settings.datastore.UserSettings

class CommandRecognitionPolicyTest : StringSpec({
    "UNSET and UNRECOGNIZED resolve to Android online" {
        CommandRecognitionPolicy.resolveEngine(
            CommandRecognitionEngine.COMMAND_RECOGNITION_ENGINE_UNSET,
        ) shouldBe CommandRecognitionEngine.COMMAND_RECOGNITION_ENGINE_ANDROID_ONLINE
        CommandRecognitionPolicy.resolveEngine(
            CommandRecognitionEngine.UNRECOGNIZED,
        ) shouldBe CommandRecognitionEngine.COMMAND_RECOGNITION_ENGINE_ANDROID_ONLINE
        CommandRecognitionPolicy.isAndroidOnline(
            UserSettings.getDefaultInstance(),
        ).shouldBeTrue()
    }

    "online engine does not initialize Vosk" {
        CommandRecognitionPolicy.shouldConstructVosk(
            CommandRecognitionEngine.COMMAND_RECOGNITION_ENGINE_ANDROID_ONLINE,
        ).shouldBeFalse()
        CommandRecognitionPolicy.shouldInitializeVoskAtStartup(
            CommandRecognitionEngine.COMMAND_RECOGNITION_ENGINE_UNSET,
        ).shouldBeFalse()
        CommandRecognitionPolicy.shouldConstructVosk(
            CommandRecognitionEngine.COMMAND_RECOGNITION_ENGINE_VOSK_LEGACY,
        ).shouldBeTrue()
    }

    "hub must be fully released before SpeechRecognizer starts" {
        CommandRecognitionPolicy.canStartAndroidRecognizer(hubRecording = true).shouldBeFalse()
        CommandRecognitionPolicy.canStartAndroidRecognizer(hubRecording = false).shouldBeTrue()
        CommandRecognitionPolicy.microphoneOwnersOverlap(
            hubRecording = true,
            speechRecognizerActive = true,
        ).shouldBeTrue()
        CommandRecognitionPolicy.microphoneOwnersOverlap(
            hubRecording = false,
            speechRecognizerActive = true,
        ).shouldBeFalse()
    }

    "vi-VN free-form partial-results intent configuration is exact" {
        val cfg = CommandRecognitionPolicy.recognizerIntentConfig()
        cfg.action shouldBe "android.speech.action.RECOGNIZE_SPEECH"
        cfg.languageModel shouldBe "free_form"
        cfg.language shouldBe "vi-VN"
        cfg.partialResults shouldBe true
        cfg.maxResults shouldBe 3
        cfg.preferOffline shouldBe false
        CommandRecognitionPolicy.shouldLaunchRecognizerActivity().shouldBeFalse()
        CommandRecognitionPolicy.shouldLaunchBrowserSearch().shouldBeFalse()
    }

    "SpeechRecognizer is destroyed on result, error, and timeout" {
        CommandRecognitionPolicy.RecognizerTerminal.entries.forEach {
            CommandRecognitionPolicy.shouldDestroyRecognizerOn(it).shouldBeTrue()
        }
    }

    "MODE to TTS onStart target is 500 ms" {
        CommandRecognitionPolicy.modeTtsStartWithinBudget(1_000L, 1_400L).shouldBeTrue()
        CommandRecognitionPolicy.modeTtsStartWithinBudget(1_000L, 1_500L).shouldBeTrue()
        CommandRecognitionPolicy.modeTtsStartWithinBudget(1_000L, 1_501L).shouldBeFalse()
        CommandRecognitionPolicy.MODE_TTS_START_BUDGET_MS shouldBe 500L
        CommandRecognitionPolicy.ANDROID_ECHO_GUARD_MS shouldBe 300L
    }

    "never bind this app's own RecognitionService; prefer Google" {
        val self = "org.stypox.dicio.cursorcommandcaptureautouic7c4"
        CommandRecognitionPolicy.pickExternalRecognitionService(
            self,
            listOf(
                CommandRecognitionPolicy.RecognitionServiceCandidate(
                    self,
                    "org.stypox.dicio.io.input.stt_service.SttService",
                ),
            ),
        ).shouldBeNull()

        val google = CommandRecognitionPolicy.RecognitionServiceCandidate(
            "com.google.android.googlequicksearchbox",
            "com.google.android.voicesearch.serviceapi.GoogleRecognitionService",
        )
        val other = CommandRecognitionPolicy.RecognitionServiceCandidate(
            "com.iflytek.speech",
            "com.iflytek.speech.SpeechService",
        )
        CommandRecognitionPolicy.pickExternalRecognitionService(
            self,
            listOf(
                CommandRecognitionPolicy.RecognitionServiceCandidate(
                    self,
                    "org.stypox.dicio.io.input.stt_service.SttService",
                ),
                other,
                google,
            ),
        ) shouldBe google
    }

    "partial and final utterances reach the existing Vietnamese router seam" {
        CommandRecognitionPolicy.finalUtterances(
            listOf("Mấy giờ rồi?", "mày giờ rồi"),
            listOf(0.91f, 0.40f),
        ) shouldBe listOf("Mấy giờ rồi?" to 0.91f, "mày giờ rồi" to 0.40f)
        CommandRecognitionPolicy.finalUtterances(listOf("  "), null).isEmpty().shouldBeTrue()
    }

    "first run of this APK persists Android online and wake OFF" {
        val old = UserSettings.getDefaultInstance().toBuilder()
            .setBackgroundWake(BackgroundWake.BACKGROUND_WAKE_ENABLED)
            .build()
        CommandRecognitionPolicy.needsAcceptanceProfileMigration(old).shouldBeTrue()
        val migrated = CommandRecognitionPolicy.applyAcceptanceProfile(old)
        migrated.commandRecognitionEngine shouldBe
            CommandRecognitionEngine.COMMAND_RECOGNITION_ENGINE_ANDROID_ONLINE
        migrated.backgroundWake shouldBe BackgroundWake.BACKGROUND_WAKE_DISABLED
        CommandRecognitionPolicy.needsAcceptanceProfileMigration(migrated).shouldBeFalse()
    }
})
