package org.stypox.dicio.io.session

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

class CarfuActivationSourceTest : StringSpec({
    beforeTest {
        CarfuActivationSource.resetForTests()
    }

    "automatic false or empty wake stays silent and uses the 10s cooldown" {
        CarfuActivationSource.markAutomaticWake()
        CarfuActivationSource.isManual().shouldBeFalse()
        CarfuActivationSource.shouldSpeakUnclear().shouldBeFalse()
        CarfuActivationSource.shouldSpeakUnclear().shouldBeFalse()
        CarfuActivationSource.shouldApplyFalseWakeCooldown().shouldBeTrue()
    }

    "manual microphone may speak unclear once" {
        CarfuActivationSource.markManualMic()
        CarfuActivationSource.isManual().shouldBeTrue()
        CarfuActivationSource.isUserInitiated().shouldBeTrue()
        CarfuActivationSource.shouldSpeakUnclear().shouldBeTrue()
        CarfuActivationSource.shouldSpeakUnclear().shouldBeFalse()
        CarfuActivationSource.shouldApplyFalseWakeCooldown().shouldBeFalse()
    }

    "hardware MODE button speaks unclear once and skips the 10s false-wake cooldown" {
        CarfuActivationSource.markHardwareButton()
        CarfuActivationSource.isManual().shouldBeFalse()
        CarfuActivationSource.isUserInitiated().shouldBeTrue()
        CarfuActivationSource.kind shouldBe CarfuActivationSource.Kind.HARDWARE_BUTTON
        CarfuActivationSource.shouldSpeakUnclear().shouldBeTrue()
        CarfuActivationSource.shouldSpeakUnclear().shouldBeFalse()
        CarfuActivationSource.shouldApplyFalseWakeCooldown().shouldBeFalse()
    }
})
