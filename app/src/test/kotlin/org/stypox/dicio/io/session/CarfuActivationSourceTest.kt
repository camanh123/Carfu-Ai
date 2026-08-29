package org.stypox.dicio.io.session

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue

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
        CarfuActivationSource.shouldSpeakUnclear().shouldBeTrue()
        CarfuActivationSource.shouldSpeakUnclear().shouldBeFalse()
        CarfuActivationSource.shouldApplyFalseWakeCooldown().shouldBeFalse()
    }
})
