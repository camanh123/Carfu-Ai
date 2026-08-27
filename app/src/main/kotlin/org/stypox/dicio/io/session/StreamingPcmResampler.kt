package org.stypox.dicio.io.session

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Linear resampler that keeps fractional source position across [AudioRecord] buffer boundaries
 * so consecutive buffers do not independently round in a way that drops or duplicates samples.
 *
 * Output is always clipped to the 16-bit range. Callers must reuse [output] across reads; this
 * class itself allocates nothing on the resample path.
 */
class StreamingPcmResampler(
    private val fromRate: Int,
    private val toRate: Int = AudioCaptureConfig.MODEL_RATE_HZ,
) {
    private val step = if (toRate == 0) 1.0 else fromRate.toDouble() / toRate
    private var phase = 0.0
    private var lastSample: Short = 0
    private var haveLast = false

    fun maxOutputLength(inputLength: Int): Int {
        if (inputLength <= 0) return 0
        if (fromRate == toRate || fromRate <= 0) return inputLength
        return ((inputLength.toLong() * toRate + fromRate - 1) / fromRate).toInt() + 4
    }

    /**
     * Resamples [length] samples from [input] into [output]. Returns the number of output samples
     * written. Does not flush a trailing fractional sample; that is carried into the next call
     * so 48k/44.1k/32k/8k streams stay continuous. Use [flush] only at end-of-stream.
     */
    fun resample(input: ShortArray, length: Int, output: ShortArray): Int {
        if (length <= 0 || output.isEmpty()) return 0
        val safeLength = length.coerceAtMost(input.size)
        if (fromRate == toRate) {
            val n = safeLength.coerceAtMost(output.size)
            input.copyInto(output, 0, 0, n)
            lastSample = input[n - 1]
            haveLast = true
            phase = 0.0
            return n
        }

        var t = phase
        var outIndex = 0
        while (outIndex < output.size && canEmit(t, safeLength)) {
            output[outIndex++] = interpolate(t, input, safeLength)
            t += step
        }
        lastSample = input[safeLength - 1]
        haveLast = true
        phase = t - safeLength
        return outIndex
    }

    /**
     * Emits leftover fractional samples at end-of-stream using the last input sample (zero-order
     * hold). Used by the one-shot helper; the live capture loop must not call this between buffers.
     */
    fun flush(output: ShortArray, offset: Int = 0): Int {
        if (!haveLast || offset >= output.size) return 0
        var t = phase
        var outIndex = offset
        while (outIndex < output.size && t < -1e-9) {
            output[outIndex++] = lastSample
            t += step
        }
        phase = t
        return outIndex - offset
    }

    private fun canEmit(t: Double, length: Int): Boolean {
        val idx = floor(t).toInt()
        val frac = t - idx
        if (!sampleAvailable(idx, length)) return false
        if (frac < 1e-9) return true
        return sampleAvailable(idx + 1, length)
    }

    private fun sampleAvailable(index: Int, length: Int): Boolean {
        return (index == -1 && haveLast) || (index in 0 until length)
    }

    private fun interpolate(t: Double, input: ShortArray, length: Int): Short {
        val idx = floor(t).toInt()
        val frac = t - idx
        val a = sampleAt(idx, input, length).toInt()
        if (frac < 1e-9) {
            return a.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        val b = sampleAt(idx + 1, input, length).toInt()
        val mixed = a + (b - a) * frac
        return mixed.roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }

    private fun sampleAt(index: Int, input: ShortArray, length: Int): Short {
        return when {
            index < 0 -> lastSample
            index < length -> input[index]
            else -> input[length - 1]
        }
    }
}
