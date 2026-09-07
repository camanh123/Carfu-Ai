package org.stypox.dicio.io.session

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

class CarfuActivationSourceTest : StringSpec({
    beforeTest {
        CarfuActivationSource.resetForTests()
        VoiceSessionManager.resetForTests()
    }

    "automatic false or empty wake stays silent and uses the 10s cooldown" {
        CarfuActivationSource.markAutomaticWake()
        CarfuActivationSource.isManual().shouldBeFalse()
        CarfuActivationSource.shouldSpeakUnclear().shouldBeFalse()
        CarfuActivationSource.shouldApplyFalseWakeCooldown().shouldBeTrue()
    }

    "V2: manual microphone no-speech exits silently" {
        CarfuActivationSource.markManualMic()
        CarfuActivationSource.isManual().shouldBeTrue()
        CarfuActivationSource.isUserInitiated().shouldBeTrue()
        CarfuActivationSource.shouldSpeakUnclear().shouldBeFalse()
        CarfuActivationSource.shouldApplyFalseWakeCooldown().shouldBeFalse()
        VoiceSessionManager.shouldSpeakNoSpeechPrompt().shouldBeFalse()
    }

    "V2: hardware MODE no-speech exits silently and skips false-wake cooldown" {
        CarfuActivationSource.markHardwareButton()
        CarfuActivationSource.isManual().shouldBeFalse()
        CarfuActivationSource.isUserInitiated().shouldBeTrue()
        CarfuActivationSource.kind shouldBe CarfuActivationSource.Kind.HARDWARE_BUTTON
        CarfuActivationSource.shouldSpeakUnclear().shouldBeFalse()
        CarfuActivationSource.shouldApplyFalseWakeCooldown().shouldBeFalse()
    }

    "automatic session origin stays silent even if global kind is overwritten" {
        CarfuActivationSource.markAutomaticWake()
        CarfuActivationSource.markManualMic()
        CarfuActivationSource.shouldSpeakUnclear(
            CarfuActivationSource.Kind.AUTOMATIC_WAKE,
        ).shouldBeFalse()
        CarfuActivationSource.shouldApplyFalseWakeCooldown(
            CarfuActivationSource.Kind.AUTOMATIC_WAKE,
        ).shouldBeTrue()
        CarfuActivationSource.shouldSpeakUnclear(
            CarfuActivationSource.Kind.MANUAL_MIC,
        ).shouldBeFalse()
    }
})
