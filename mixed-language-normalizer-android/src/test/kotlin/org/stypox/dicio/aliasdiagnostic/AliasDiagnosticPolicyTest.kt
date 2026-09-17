package org.stypox.dicio.aliasdiagnostic

import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File

class AliasDiagnosticPolicyTest : StringSpec({
    "identity is a unique applicationId alongside production CARFU" {
        AliasDiagnosticPolicy.APPLICATION_ID shouldBe "org.stypox.dicio.aliasdiagnostic"
        AliasDiagnosticPolicy.APPLICATION_ID shouldBe BuildConfig.APPLICATION_ID
        AliasDiagnosticPolicy.APPLICATION_ID shouldBe BuildConfig.APPLICATION_ID
    }

    "SpeechRecognizer locale is vi-VN" {
        AliasDiagnosticPolicy.SPEECH_LOCALE shouldBe "vi-VN"
    }

    "diagnostic APK is not production-wired and launches nothing" {
        AliasDiagnosticPolicy.PRODUCTION_WIRED shouldBe "NO"
        AliasDiagnosticPolicy.EXECUTES_COMMANDS.shouldBeFalse()
        AliasDiagnosticPolicy.LAUNCHES_SMARTTUBE.shouldBeFalse()
        AliasDiagnosticPolicy.LAUNCHES_YOUTUBE.shouldBeFalse()
        AliasDiagnosticPolicy.LAUNCHES_MAPS.shouldBeFalse()
        AliasDiagnosticPolicy.CALLS_PRODUCTION_NLU.shouldBeFalse()
        AliasDiagnosticPolicy.USES_ACCESSIBILITY.shouldBeFalse()
        AliasDiagnosticPolicy.BECOMES_DEFAULT_ASSISTANT.shouldBeFalse()
        AliasDiagnosticPolicy.USES_BACKGROUND_WAKE.shouldBeFalse()
    }

    "error names cover SpeechRecognizer codes used on device" {
        AliasDiagnosticActivity.errorName(SpeechRecognizer.ERROR_NO_MATCH) shouldBe
            "ERROR_NO_MATCH"
        AliasDiagnosticActivity.errorName(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) shouldBe
            "ERROR_INSUFFICIENT_PERMISSIONS"
        AliasDiagnosticActivity.errorName(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) shouldBe
            "ERROR_LANGUAGE_NOT_SUPPORTED"
    }

    "manifest stays listen-only: RECORD_AUDIO, no assistant/a11y/query-all" {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        manifest shouldContain "android.permission.RECORD_AUDIO"
        manifest shouldContain "android.speech.RecognitionService"
        manifest shouldContain ".AliasDiagnosticActivity"
        manifest shouldContain "LEANBACK_LAUNCHER"
        manifest.shouldNotContain("QUERY_ALL_PACKAGES")
        manifest.shouldNotContain("BIND_ACCESSIBILITY_SERVICE")
        manifest.shouldNotContain("BIND_VOICE_INTERACTION")
        manifest.shouldNotContain("VoiceInteractionService")
        manifest.shouldNotContain("android.intent.action.ASSIST")
        manifest.shouldNotContain("FOREGROUND_SERVICE")
        manifest.shouldNotContain("org.smarttube")
        manifest.shouldNotContain("com.google.android.apps.maps")
        manifest.shouldNotContain("INTERNET")
    }

    "activity source does not launch media/nav or call production NLU" {
        val src = File("src/main/kotlin/org/stypox/dicio/aliasdiagnostic/AliasDiagnosticActivity.kt")
            .readText()
        src shouldContain "RecognizerIntent.ACTION_RECOGNIZE_SPEECH"
        src shouldContain "AliasDiagnosticPolicy.SPEECH_LOCALE"
        src shouldContain "DiagnosticDisplay.defaultNormalizer"
        src shouldContain "DiagnosticSession.begin"
        src shouldContain "DiagnosticDisplay.of"
        src.shouldNotContain("ACTION_VIEW")
        src.shouldNotContain("startActivity")
        src.shouldNotContain("AccessibilityService")
        src.shouldNotContain("VietnameseCommandUnderstanding")
        src.shouldNotContain("SkillEvaluator")
        src.shouldNotContain("VoiceSession")
        src.shouldNotContain("org.stypox.dicio.skills")
        src.shouldNotContain("org.smarttube.stable")
        RecognizerIntent.ACTION_RECOGNIZE_SPEECH shouldBe "android.speech.action.RECOGNIZE_SPEECH"
    }

    "display layer calls frozen engine resolve and does not special-case providers" {
        val display = File("src/main/kotlin/org/stypox/dicio/aliasdiagnostic/DiagnosticDisplay.kt")
            .readText()
        display shouldContain "normalizer.resolve"
        display shouldContain "ProviderResolution"
        display.shouldNotContain("spotify ->")
        display.shouldNotContain("SmartTube")
        val activity = File("src/main/kotlin/org/stypox/dicio/aliasdiagnostic/AliasDiagnosticActivity.kt")
            .readText()
        activity.shouldNotContain("\"spotify\"")
    }
})
