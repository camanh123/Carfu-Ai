package org.stypox.dicio.io.session

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

class AudioCaptureConfigTest : StringSpec({
    "resample 48 kHz capture down to the 16 kHz Vosk model rate" {
        val input = ShortArray(4800) { i -> (i % 100).toShort() }
        val out = AudioCaptureConfig.resampleToModelRate(input, input.size, 48_000)
        out.size shouldBeGreaterThan 1500
        out.size shouldBe 1600
    }

    "resample is a no-op at 16 kHz" {
        val input = shortArrayOf(1, 2, 3, 4)
        val out = AudioCaptureConfig.resampleToModelRate(input, input.size, 16_000)
        out.toList() shouldBe input.toList()
    }
})
