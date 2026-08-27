package org.stypox.dicio.io.session

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe

class AudioCaptureConfigTest : StringSpec({
    "fallback rates are probed in the required production order" {
        AudioCaptureConfig.FALLBACK_RATES.toList() shouldContainExactly listOf(
            48_000, 44_100, 32_000, 8_000,
        )
    }

    "detect prefers native 16 kHz when the probe reports it as valid" {
        val probe = CaptureRateProbe { rate ->
            if (rate == 16_000) 3200 else 0
        }
        val cfg = AudioCaptureConfig.detect(probe = probe)
        cfg.captureRateHz shouldBe 16_000
        cfg.needsResample shouldBe false
    }

    "detect selects the first supported fallback when 16 kHz is unavailable" {
        val probe = CaptureRateProbe { rate ->
            when (rate) {
                44_100 -> 4096
                32_000 -> 2048
                else -> -1
            }
        }
        val cfg = AudioCaptureConfig.detect(probe = probe)
        cfg.captureRateHz shouldBe 44_100
        cfg.needsResample shouldBe true
    }

    "openFirstFallback skips invalid min-buffer rates and failed openers" {
        val attempted = mutableListOf<Int>()
        val probe = CaptureRateProbe { rate ->
            when (rate) {
                48_000 -> -2
                44_100 -> 4096
                32_000 -> 2048
                8_000 -> 1024
                else -> -1
            }
        }
        val opened = AudioCaptureConfig.openFirstFallback(probe = probe) { rate, buf ->
            attempted.add(rate)
            if (rate == 44_100) {
                null
            } else {
                rate to buf
            }
        }
        attempted shouldContainExactly listOf(44_100, 32_000)
        opened shouldBe (32_000 to 2048.coerceAtLeast(2048))
    }

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

    "rate conversion covers 48k, 44.1k, 32k and 8k" {
        fun convertedSize(fromRate: Int, inputLength: Int): Int {
            val input = ShortArray(inputLength) { i -> (i % 50).toShort() }
            return AudioCaptureConfig.resampleToModelRate(input, input.size, fromRate).size
        }
        convertedSize(48_000, 4800) shouldBe 1600
        convertedSize(44_100, 4410) shouldBeInRange 1590..1610
        convertedSize(32_000, 3200) shouldBe 1600
        convertedSize(8_000, 800) shouldBeInRange 1590..1610
    }

    "8 kHz upsampling produces more samples than the input" {
        val input = ShortArray(400) { i -> (i * 3).toShort() }
        val out = AudioCaptureConfig.resampleToModelRate(input, input.size, 8_000)
        out.size shouldBeGreaterThan input.size
        out.size shouldBeInRange 790..810
    }

    "resampling clips to the 16-bit range" {
        val input = ShortArray(48) { Short.MAX_VALUE }
        val out = AudioCaptureConfig.resampleToModelRate(input, input.size, 48_000)
        out.forEach { sample ->
            sample.toInt() shouldBeInRange (Short.MIN_VALUE.toInt()..Short.MAX_VALUE.toInt())
        }
    }

    "streaming resample stays continuous across buffer boundaries" {
        fun assertContinuous(fromRate: Int, total: Int, split: Int) {
            val full = ShortArray(total) { i -> ((i * 13) % 1000 - 500).toShort() }
            val oneShot = StreamingPcmResampler(fromRate)
            val oneBuf = ShortArray(oneShot.maxOutputLength(full.size) + 16)
            val oneN = oneShot.resample(full, full.size, oneBuf)
            val expected = oneBuf.copyOf(oneN).toList()

            val streamed = StreamingPcmResampler(fromRate)
            val firstIn = full.copyOfRange(0, split)
            val secondIn = full.copyOfRange(split, full.size)
            val a = ShortArray(streamed.maxOutputLength(firstIn.size) + 16)
            val n1 = streamed.resample(firstIn, firstIn.size, a)
            val b = ShortArray(streamed.maxOutputLength(secondIn.size) + 16)
            val n2 = streamed.resample(secondIn, secondIn.size, b)
            (a.copyOf(n1).toList() + b.copyOf(n2).toList()) shouldBe expected
        }
        assertContinuous(48_000, 4800, 1600)
        assertContinuous(44_100, 9000, 4096)
        assertContinuous(32_000, 3200, 1000)
        assertContinuous(8_000, 800, 300)
    }
})
