package org.stypox.dicio.io.input

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.stypox.dicio.io.session.CommandSessionPhase
import org.stypox.dicio.io.session.VoiceSessionManager
import org.stypox.dicio.ui.ambient.AmbientVoiceAttachPolicy

class ListeningCueSafetyTest : StringSpec({
    "null MediaPlayer create result is not usable" {
        ListeningCueSafety.isCreatedPlayerUsable(null).shouldBeFalse()
    }

    "non-null player is usable" {
        ListeningCueSafety.isCreatedPlayerUsable(Any()).shouldBeTrue()
    }

    "listening cue plays only when STT becomes Listening before COMMAND_LISTENING" {
        ListeningCueSafety.shouldPlayListeningCue(
            becameListening = true,
            phase = CommandSessionPhase.WAKE_DETECTED,
        ).shouldBeTrue()
        ListeningCueSafety.shouldPlayListeningCue(
            becameListening = true,
            phase = CommandSessionPhase.IDLE_WAKE,
        ).shouldBeTrue()
        ListeningCueSafety.shouldPlayListeningCue(
            becameListening = true,
            phase = CommandSessionPhase.COMMAND_LISTENING,
        ).shouldBeFalse()
        ListeningCueSafety.shouldPlayListeningCue(
            becameListening = true,
            phase = CommandSessionPhase.ACKNOWLEDGING,
        ).shouldBeFalse()
        ListeningCueSafety.shouldPlayListeningCue(
            becameListening = false,
            phase = CommandSessionPhase.WAKE_DETECTED,
        ).shouldBeFalse()
    }

    "phase4.1 voice contracts preserved with cue safety" {
        VoiceSessionManager.modeUsesSpokenAck().shouldBeFalse()
        VoiceSessionManager.NO_SPEECH_TIMEOUT_MS shouldBe 5_000L
        CommandRecognitionPolicy.MAX_SR_REARMS shouldBe 0
        AmbientVoiceAttachPolicy.CROSS_APP_WINDOW_ATTACH_ENABLED.shouldBeFalse()
    }

    "phase4.2: cue is skipped once COMMAND_LISTENING is entered before SR start" {
        SpeechRecognizerSessionPolicy.markCommandListeningBeforeStartListening().shouldBeTrue()
        ListeningCueSafety.shouldPlayListeningCue(
            becameListening = true,
            phase = CommandSessionPhase.COMMAND_LISTENING,
        ).shouldBeFalse()
        ListeningCueSafety.isCreatedPlayerUsable(null).shouldBeFalse()
    }
})
