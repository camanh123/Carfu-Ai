package org.stypox.dicio.ui.ambient

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.stypox.dicio.io.input.CommandRecognitionPolicy
import org.stypox.dicio.io.session.VoiceSessionManager

class AmbientOverlayPermissionTest : StringSpec({
    beforeTest {
        AmbientOverlayPermission.resetForTests()
    }

    afterTest {
        AmbientOverlayPermission.resetForTests()
    }

    "permission state detection reflects grant override" {
        AmbientOverlayPermission.overrideGranted = true
        AmbientOverlayPermission.isGranted().shouldBeTrue()
        AmbientOverlayPermission.overrideGranted = false
        AmbientOverlayPermission.isGranted().shouldBeFalse()
    }

    "settings intent targets ACTION_MANAGE_OVERLAY_PERMISSION for package" {
        AmbientOverlayPermission.ACTION_MANAGE_OVERLAY_PERMISSION shouldBe
            "android.settings.action.MANAGE_OVERLAY_PERMISSION"
        AmbientOverlayPermission.ACTION_MANAGE_OVERLAY_PERMISSION shouldBe
            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION
        val uri = AmbientOverlayPermission.packageOverlayUriString("org.stypox.dicio")
        uri.shouldStartWith("package:")
        uri.shouldContain("org.stypox.dicio")
        uri shouldBe "package:org.stypox.dicio"
    }

    "overlay permission helpers never own voice lifecycle" {
        AmbientVoicePresentation.mayCreateOrRestartVoiceSession().shouldBeFalse()
        VoiceSessionManager.modeUsesSpokenAck().shouldBeFalse()
        VoiceSessionManager.NO_SPEECH_TIMEOUT_MS shouldBe 5_000L
        CommandRecognitionPolicy.MAX_SR_REARMS shouldBe 0
    }
})
