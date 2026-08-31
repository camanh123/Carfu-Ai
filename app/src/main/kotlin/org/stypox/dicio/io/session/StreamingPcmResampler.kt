package org.stypox.dicio.io.session

import kotlin.math.roundToInt

/**
 * Linear resampler that keeps source position in integer sample counts so consecutive
 * [AudioRecord] buffers do not independently round in a way that drops or duplicates samples.
 *
 * Output is always clipped to the 16-bit range. Callers must reuse [output] across reads; this
 * class itself allocates nothing on the resample path.
 */
class StreamingPcmResampler(
    private val fromRate: Int,
    private val toRate: Int = AudioCaptureConfig.MODEL_RATE_HZ,
) {
    private var outputIndex = 0L
    private var inputSamplesSeen = 0L
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
        if (fromRate == toRate || toRate <= 0) {
            val n = safeLength.coerceAtMost(output.size)
            input.copyInto(output, 0, 0, n)
            lastSample = input[n - 1]
            haveLast = true
            inputSamplesSeen += n
            outputIndex += n
            return n
        }

        var written = 0
        val startAbs = inputSamplesSeen
        val endAbs = startAbs + safeLength
        while (written < output.size) {
            val numer = outputIndex * fromRate.toLong()
            val srcAbs = numer / toRate
            val fracN = numer % toRate
            if (srcAbs >= endAbs) break
            if (srcAbs < startAbs - 1L) break
            if (srcAbs < startAbs && !haveLast) break
            val needNext = fracN != 0L
            if (needNext && srcAbs + 1L >= endAbs) break

            val a = sampleAbs(srcAbs, startAbs, input, safeLength)
            val value = if (!needNext) {
                a
            } else {
                val b = sampleAbs(srcAbs + 1L, startAbs, input, safeLength)
                a + (b - a) * (fracN.toDouble() / toRate)
            }
            output[written] = value.roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            written += 1
            outputIndex += 1
        }
        lastSample = input[safeLength - 1]
        haveLast = true
        inputSamplesSeen = endAbs
        return written
    }

    /**
     * Emits leftover fractional samples at end-of-stream using the last input sample (zero-order
     * hold). Used by the one-shot helper; the live capture loop must not call this between buffers.
     */
    fun flush(output: ShortArray, offset: Int = 0): Int {
        if (!haveLast || offset >= output.size || toRate <= 0) return 0
        var written = offset
        val lastAbs = inputSamplesSeen - 1L
        while (written < output.size) {
            val srcAbs = outputIndex * fromRate.toLong() / toRate
            if (srcAbs > lastAbs) break
            output[written] = lastSample
            written += 1
            outputIndex += 1
        }
        return written - offset
    }

    private fun sampleAbs(
        abs: Long,
        startAbs: Long,
        input: ShortArray,
        length: Int,
    ): Double {
        if (abs < startAbs) return lastSample.toDouble()
        val local = (abs - startAbs).toInt()
        return if (local in 0 until length) input[local].toDouble() else lastSample.toDouble()
    }
}
