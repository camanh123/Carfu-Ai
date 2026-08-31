package org.stypox.dicio.io.session

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.longs.shouldBeExactly
import io.kotest.matchers.shouldBe
import org.stypox.dicio.io.input.CommandRecognitionPolicy

class CarfuLatencyLogTest : StringSpec({
    beforeTest { CarfuLatencyLog.resetForTests() }

    "MODE intent to TTS onStart records the 500 ms budget seam" {
        val clock = mutableListOf(10_000L)
        CarfuLatencyLog.nowMs = { clock[0] }
        CarfuLatencyLog.onModeIntent()
        CarfuLatencyLog.bindSession(7L)
        clock[0] = 10_320L
        CarfuLatencyLog.mark(CarfuLatencyLog.Mark.TTS_ON_START)
        CarfuLatencyLog.deltaMs(
            CarfuLatencyLog.Mark.MODE_INTENT,
            CarfuLatencyLog.Mark.TTS_ON_START,
        ) shouldBeExactly 320L
        CommandRecognitionPolicy.modeTtsStartWithinBudget(
            CarfuLatencyLog.millis(CarfuLatencyLog.Mark.MODE_INTENT),
            CarfuLatencyLog.millis(CarfuLatencyLog.Mark.TTS_ON_START),
        ).shouldBeTrue()
        CarfuLatencyLog.sessionId shouldBe 7L
    }

    "first partial is recorded once and never logs the utterance" {
        val clock = mutableListOf(1L)
        CarfuLatencyLog.nowMs = { clock[0] }
        CarfuLatencyLog.onModeIntent()
        clock[0] = 50L
        CarfuLatencyLog.mark(CarfuLatencyLog.Mark.FIRST_PARTIAL)
        clock[0] = 80L
        CarfuLatencyLog.mark(CarfuLatencyLog.Mark.FIRST_PARTIAL)
        CarfuLatencyLog.millis(CarfuLatencyLog.Mark.FIRST_PARTIAL) shouldBeExactly 50L
    }
})
