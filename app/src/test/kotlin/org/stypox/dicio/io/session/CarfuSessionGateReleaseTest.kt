package org.stypox.dicio.io.session

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

class CarfuSessionGateReleaseTest : StringSpec({
    beforeTest { CarfuSessionGate.resetForTests() }

    "UI empty session does not apply empty restart cooldown" {
        CarfuSessionGate.requestStart(
            origin = CarfuSessionGate.Origin.UI,
            phase = CommandSessionPhase.IDLE_WAKE,
            startSession = { 81L },
        )
        CarfuSessionGate.onSessionFinished(
            sessionId = 81L,
            hadTranscript = false,
            origin = CarfuSessionGate.Origin.UI,
        )
        CarfuSessionGate.isModeReady().shouldBeTrue()
        val next = CarfuSessionGate.requestStart(
            origin = CarfuSessionGate.Origin.UI,
            phase = CommandSessionPhase.IDLE_WAKE,
            startSession = { 82L },
        )
        next.accepted.shouldBeTrue()
        next.sessionId shouldBe 82L
    }

    "finished session id is stale and cannot be current" {
        CarfuSessionGate.requestStart(
            origin = CarfuSessionGate.Origin.HARDWARE_BUTTON,
            phase = CommandSessionPhase.IDLE_WAKE,
            startSession = { 91L },
        )
        CarfuSessionGate.onSessionFinished(
            sessionId = 91L,
            hadTranscript = false,
            origin = CarfuSessionGate.Origin.HARDWARE_BUTTON,
        )
        CarfuSessionGate.isCurrent(91L).shouldBeFalse()
        CarfuSessionGate.activeSessionId shouldBe 0L
    }
})
