package org.stypox.dicio.ui.ambient

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.stypox.dicio.io.input.CommandRecognitionPolicy
import org.stypox.dicio.io.session.VoiceSessionManager

class AmbientVoiceAttachPolicyTest : StringSpec({
    "phase 4.1 disables cross-app WindowManager attach so listening is not blocked" {
        AmbientVoiceAttachPolicy.CROSS_APP_WINDOW_ATTACH_ENABLED.shouldBeFalse()
        AmbientVoiceAttachPolicy.shouldAttachCrossAppOverlay(
            canDrawOverlays = true,
            hudVisible = true,
        ).shouldBeFalse()
        AmbientVoiceAttachPolicy.shouldAttachCrossAppOverlay(
            canDrawOverlays = false,
            hudVisible = true,
        ).shouldBeFalse()
    }

    "saved-state bootstrap order requires performAttach before performRestore" {
        val steps = AmbientVoiceAttachPolicy.savedStateBootstrapSteps()
        steps.shouldContainExactly(
            "performAttach",
            "performRestore",
            "ON_CREATE",
            "ON_START",
            "ON_RESUME",
        )
        steps.indexOf("performAttach") shouldBe 0
        steps.indexOf("performRestore") shouldBe 1
        (steps.indexOf("performAttach") < steps.indexOf("performRestore")).shouldBeTrue()
    }

    "attach policy does not own voice lifecycle contracts" {
        AmbientVoicePresentation.mayCreateOrRestartVoiceSession().shouldBeFalse()
        VoiceSessionManager.modeUsesSpokenAck().shouldBeFalse()
        VoiceSessionManager.NO_SPEECH_TIMEOUT_MS shouldBe 5_000L
        CommandRecognitionPolicy.MAX_SR_REARMS shouldBe 0
    }
})
