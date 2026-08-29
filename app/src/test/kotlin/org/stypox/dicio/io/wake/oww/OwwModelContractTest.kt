package org.stypox.dicio.io.wake.oww

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.stypox.dicio.io.wake.WakeAcceptancePolicy

class OwwModelContractTest : StringSpec({
    "PCM is normalized with 32768 scale to approximately minus one to one" {
        OwwModel.PCM_SCALE shouldBe 32768.0f
        (32767.toFloat() / OwwModel.PCM_SCALE) shouldBeGreaterThan 0.99f
        ((-32768).toFloat() / OwwModel.PCM_SCALE) shouldBe -1.0f
    }

    "mel embedding and wake tensor shapes plus positive output index are documented" {
        OwwModel.MEL_INPUT_COUNT shouldBe 1152
        OwwModel.MEL_OUTPUT_COUNT shouldBe 5
        OwwModel.MEL_FEATURE_SIZE shouldBe 32
        OwwModel.EMB_INPUT_COUNT shouldBe 76
        OwwModel.EMB_FEATURE_SIZE shouldBe 96
        OwwModel.WAKE_INPUT_COUNT shouldBe 16
        OwwModel.WAKE_OUTPUT_SIZE shouldBe 1
        OwwModel.POSITIVE_OUTPUT_INDEX shouldBe 0
        OwwModel.POSITIVE_OUTPUT_INDEX shouldBe
            OwwModel.POSITIVE_OUTPUT_INDEX.coerceIn(0, OwwModel.WAKE_OUTPUT_SIZE - 1)
    }

    "non-finite or out-of-range scores collapse to the documented unit interval" {
        OwwModel.sanitizeScore(Float.NaN) shouldBe 0.0f
        OwwModel.sanitizeScore(Float.POSITIVE_INFINITY) shouldBe 0.0f
        OwwModel.sanitizeScore(-0.2f) shouldBe 0.0f
        OwwModel.sanitizeScore(0.91f) shouldBe 0.91f
        WakeAcceptancePolicy.CARFU_WAKE_THRESHOLD shouldBeGreaterThan 0.8f
    }
})
