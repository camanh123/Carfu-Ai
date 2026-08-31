package org.stypox.dicio.io.wake

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

class AdaptiveVoiceActivityTest : StringSpec({
    "exact-zero frames are never voice" {
        val vad = AdaptiveVoiceActivity()
        val result = vad.observe(peak = 0, rms = 0.0)
        result.exactZero.shouldBeTrue()
        result.isVoice.shouldBeFalse()
    }

    "quiet cabin noise is not voice" {
        val vad = AdaptiveVoiceActivity()
        val result = vad.observe(peak = 20, rms = 8.0)
        result.exactZero.shouldBeFalse()
        result.isVoice.shouldBeFalse()
        vad.noiseRms.shouldBeGreaterThan(0.0)
    }

    "speech-level energy is voice" {
        val vad = AdaptiveVoiceActivity()
        vad.observe(peak = 30, rms = 10.0)
        val result = vad.observe(peak = 2_000, rms = 400.0)
        result.isVoice.shouldBeTrue()
        result.exactZero.shouldBeFalse()
    }

    "reset restores the noise floor" {
        val vad = AdaptiveVoiceActivity()
        vad.observe(peak = 10, rms = 5.0)
        vad.reset()
        vad.noiseRms shouldBe AdaptiveVoiceActivity.INITIAL_NOISE_RMS
    }
})
