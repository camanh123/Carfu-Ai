package org.stypox.dicio.io.session

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * Detects a usable [AudioRecord] configuration on UIS7862 / generic Android.
 * Vosk small models (including `vosk-model-small-vn-0.4`) expect 16 kHz PCM.
 *
 * Path A prefers native 16 kHz (existing Vosk SpeechService). Path B probes fallback rates in
 * this order and opens a real [AudioRecord] at the first rate that initializes:
 * 48000, 44100, 32000, 8000.
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
        const val AUDIO_SOURCE = MediaRecorder.AudioSource.VOICE_RECOGNITION
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        val FALLBACK_RATES = intArrayOf(48_000, 44_100, 32_000, 8_000)
        private const val TAG = "AudioCaptureConfig"

        val androidMinBufferProbe: CaptureRateProbe = CaptureRateProbe { rateHz ->
            AudioRecord.getMinBufferSize(rateHz, CHANNEL, ENCODING)
        }

        fun isNative16kHzSupported(probe: CaptureRateProbe = androidMinBufferProbe): Boolean {
            return probe.minBufferBytes(MODEL_RATE_HZ) > 0
        }

        fun detect(
            audioSource: Int = AUDIO_SOURCE,
            probe: CaptureRateProbe = androidMinBufferProbe,
        ): AudioCaptureConfig {
            if (isNative16kHzSupported(probe)) {
                val minBuf = probe.minBufferBytes(MODEL_RATE_HZ).coerceAtLeast(2048)
                CarfuLog.i(TAG, "supportedRates native=16000 source=$audioSource")
                return AudioCaptureConfig(
                    captureRateHz = MODEL_RATE_HZ,
                    modelRateHz = MODEL_RATE_HZ,
                    minBufferBytes = minBuf,
                    audioSource = audioSource,
                    needsResample = false,
                )
            }

            val fallback = firstSupportedFallback(probe)
            CarfuLog.i(
                TAG,
                "supportedRates native=none fallback=${fallback?.captureRateHz} source=$audioSource",
            )
            return fallback ?: AudioCaptureConfig(
                captureRateHz = MODEL_RATE_HZ,
                modelRateHz = MODEL_RATE_HZ,
                minBufferBytes = 2048,
                audioSource = audioSource,
                needsResample = false,
            )
        }

        fun firstSupportedFallback(
            probe: CaptureRateProbe = androidMinBufferProbe,
        ): AudioCaptureConfig? {
            for (rate in FALLBACK_RATES) {
                val minBuf = probe.minBufferBytes(rate)
                if (minBuf > 0) {
                    return AudioCaptureConfig(
                        captureRateHz = rate,
                        modelRateHz = MODEL_RATE_HZ,
                        minBufferBytes = minBuf.coerceAtLeast(2048),
                        audioSource = AUDIO_SOURCE,
                        needsResample = true,
                    )
                }
            }
            return null
        }

        /**
         * Production opener used by fallback command capture. Tries each fallback rate in order,
         * requiring both a valid min-buffer probe and a successful [opener] (real AudioRecord).
         */
        fun <T> openFirstFallback(
            rates: IntArray = FALLBACK_RATES,
            probe: CaptureRateProbe,
            opener: (rateHz: Int, bufferBytes: Int) -> T?,
        ): T? {
            for (rate in rates) {
                val minBuf = probe.minBufferBytes(rate)
                if (minBuf <= 0) continue
                val bufferBytes = minBuf.coerceAtLeast(2048)
                val opened = opener(rate, bufferBytes)
                if (opened != null) return opened
            }
            return null
        }

        fun resampleToModelRate(input: ShortArray, length: Int, fromRate: Int): ShortArray {
            val safeLength = length.coerceIn(0, input.size)
            if (fromRate == MODEL_RATE_HZ || safeLength == 0) {
                return input.copyOf(safeLength)
            }
            val resampler = StreamingPcmResampler(fromRate, MODEL_RATE_HZ)
            val out = ShortArray(resampler.maxOutputLength(safeLength) + 8)
            val n = resampler.resample(input, safeLength, out)
            val nFlush = resampler.flush(out, n)
            return out.copyOf(n + nFlush)
        }
    }
}
