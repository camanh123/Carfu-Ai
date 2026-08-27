package org.stypox.dicio.io.session

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.roundToInt

/**
 * Detects a usable [AudioRecord] configuration on UIS7862 / generic Android.
 * Vosk small models (including `vosk-model-small-vn-0.4`) expect 16 kHz PCM.
 * Wake-word capture on this device already succeeds at 16 kHz, so that rate is preferred
 * when [AudioRecord.getMinBufferSize] reports it as valid. Otherwise we record at a
 * supported rate and resample to 16 kHz before feeding the recognizer.
 */
data class AudioCaptureConfig(
    val captureRateHz: Int,
    val modelRateHz: Int,
    val minBufferBytes: Int,
    val audioSource: Int,
    val needsResample: Boolean,
) {
    companion object {
        const val MODEL_RATE_HZ = 16_000
        private const val TAG = "AudioCaptureConfig"
        private val CANDIDATE_RATES = intArrayOf(16_000, 48_000, 44_100, 32_000, 8_000)

        fun detect(
            audioSource: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION,
        ): AudioCaptureConfig {
            val supported = LinkedHashMap<Int, Int>()
            for (rate in CANDIDATE_RATES) {
                val minBuf = AudioRecord.getMinBufferSize(
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                if (minBuf > 0) {
                    supported[rate] = minBuf
                }
            }
            Log.i(TAG, "supportedRates=$supported source=$audioSource")

            val captureRate = when {
                supported.containsKey(MODEL_RATE_HZ) -> MODEL_RATE_HZ
                supported.isNotEmpty() -> supported.keys.first()
                else -> MODEL_RATE_HZ
            }
            val minBuf = supported[captureRate]
                ?: AudioRecord.getMinBufferSize(
                    captureRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                ).coerceAtLeast(captureRate / 5 * 2)

            return AudioCaptureConfig(
                captureRateHz = captureRate,
                modelRateHz = MODEL_RATE_HZ,
                minBufferBytes = minBuf.coerceAtLeast(2048),
                audioSource = audioSource,
                needsResample = captureRate != MODEL_RATE_HZ,
            )
        }

        fun resampleToModelRate(input: ShortArray, length: Int, fromRate: Int): ShortArray {
            if (fromRate == MODEL_RATE_HZ || length <= 1) {
                return input.copyOf(length)
            }
            val outLen = (length.toLong() * MODEL_RATE_HZ / fromRate).toInt().coerceAtLeast(1)
            val out = ShortArray(outLen)
            val step = fromRate.toDouble() / MODEL_RATE_HZ
            var src = 0.0
            for (i in 0 until outLen) {
                val idx = src.toInt().coerceIn(0, length - 2)
                val frac = src - idx
                val a = input[idx].toInt()
                val b = input[idx + 1].toInt()
                out[i] = (a + (b - a) * frac).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                src += step
            }
            return out
        }
    }
}
