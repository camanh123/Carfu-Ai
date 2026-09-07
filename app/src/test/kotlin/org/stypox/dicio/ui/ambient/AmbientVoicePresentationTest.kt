package org.stypox.dicio.ui.ambient

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.stypox.dicio.io.input.CommandRecognitionPolicy
import org.stypox.dicio.io.session.CommandSessionPhase
import org.stypox.dicio.io.session.VoiceSessionManager

class AmbientVoicePresentationTest : StringSpec({
    "LISTENING phases show overlay; IDLE/TERMINAL hide it" {
        AmbientVoicePresentation.fromSession(
            CommandSessionPhase.COMMAND_LISTENING,
            null,
        ).visible.shouldBeTrue()
        AmbientVoicePresentation.fromSession(
            CommandSessionPhase.WAKE_DETECTED,
            null,
        ).visible.shouldBeTrue()
        AmbientVoicePresentation.fromSession(
            CommandSessionPhase.IDLE_WAKE,
            "stale",
        ).visible.shouldBeFalse()
        AmbientVoicePresentation.fromSession(
            CommandSessionPhase.PROCESSING,
            "x",
        ).visible.shouldBeFalse()
        AmbientVoicePresentation.fromSession(
            CommandSessionPhase.RETURNING_TO_WAKE,
            null,
        ).visible.shouldBeFalse()
    }

    "transcript is raw partial only while listening; blank before speech" {
        val beforeSpeech = AmbientVoicePresentation.fromSession(
            CommandSessionPhase.COMMAND_LISTENING,
            null,
        )
        beforeSpeech.visible.shouldBeTrue()
        beforeSpeech.rawTranscript.shouldBeNull()

        val live = AmbientVoicePresentation.fromSession(
            CommandSessionPhase.COMMAND_LISTENING,
            "  Chỉ đường đến Mỹ Đình  ",
        )
        live.rawTranscript shouldBe "Chỉ đường đến Mỹ Đình"

        val idleClears = AmbientVoicePresentation.fromSession(
            CommandSessionPhase.IDLE_WAKE,
            "Chỉ đường đến Mỹ Đình",
        )
        idleClears.visible.shouldBeFalse()
        idleClears.rawTranscript.shouldBeNull()
    }

    "UI presentation cannot create or restart a voice session" {
        AmbientVoicePresentation.mayCreateOrRestartVoiceSession().shouldBeFalse()
    }

    "V2-CORE-1 invariants remain intact alongside ambient UI" {
        VoiceSessionManager.modeUsesSpokenAck().shouldBeFalse()
        CommandRecognitionPolicy.MAX_SR_REARMS shouldBe 0
        VoiceSessionManager.NO_SPEECH_TIMEOUT_MS shouldBe 5_000L
    }

    "fade duration is short (150–250ms band)" {
        AmbientVoicePresentation.FADE_MS shouldBe 200
    }
})
