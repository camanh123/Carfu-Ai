package org.stypox.dicio.io.session

import org.vosk.android.RecognitionListener
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

fun interface CaptureRateProbe {
    /** Min buffer size in bytes for PCM16 mono, or `<= 0` if the rate cannot be used. */
    fun minBufferBytes(rateHz: Int): Int
}

interface Direct16kHzCapture {
    fun isAvailable(): Boolean
    fun start(listener: RecognitionListener): Boolean
    fun stop()
    fun shutdown()
    fun isRunning(): Boolean
}

interface FallbackPcmCapture {
    fun open(rates: IntArray): FallbackCaptureSession?
}

interface FallbackCaptureSession {
    val rateHz: Int
    val bufferBytes: Int
    val suggestedReadShorts: Int
    fun start(consumer: FallbackPcmConsumer)
    fun stopAndRelease()
}

interface FallbackPcmConsumer {
    fun onPcm(samples: ShortArray, length: Int)
    fun onReadError(error: Exception)
}

interface VoskRecognizerAdapter {
    fun acceptWaveForm(samples: ShortArray, length: Int): Boolean
    fun resultJson(): String
    fun partialJson(): String
    fun finalJson(): String
}

fun interface CaptureTimeoutScheduler {
    fun schedule(delayMs: Long, onTimeout: () -> Unit): Handle

    fun interface Handle {
        fun cancel()
    }
}

enum class CommandCapturePath {
    NONE,
    DIRECT,
    FALLBACK,
}

sealed class CommandCaptureStartResult {
    data object Direct : CommandCaptureStartResult()
    data class Fallback(
        val rateHz: Int,
        val bufferBytes: Int,
    ) : CommandCaptureStartResult()
    data class Failed(val cause: Exception) : CommandCaptureStartResult()
}

/**
 * Injectable production capture seam. Path A starts the existing 16 kHz Vosk [SpeechService].
 * Path B opens a fallback [AudioRecord], resamples continuously to 16 kHz, and feeds
 * [VoskRecognizerAdapter.acceptWaveForm]. Direct and fallback never run at the same time.
 */
class CommandCaptureCoordinator(
    private val direct: Direct16kHzCapture,
    private val fallback: FallbackPcmCapture,
    private val recognizer: VoskRecognizerAdapter,
    private val timeoutMs: Long = CommandSession.COMMAND_LISTEN_TIMEOUT_MS.toLong(),
    private val timeoutScheduler: CaptureTimeoutScheduler = CaptureTimeoutScheduler { _, _ ->
        CaptureTimeoutScheduler.Handle { }
    },
) {
    private val running = AtomicBoolean(false)
    private val directRunning = AtomicBoolean(false)
    private val fallbackRunning = AtomicBoolean(false)

    var path: CommandCapturePath = CommandCapturePath.NONE
        private set
    var resampleInvocations: Int = 0
        private set
    var recognizerAccepts: Int = 0
        private set
    private var firstPcmLogged = false

    private var fallbackSession: FallbackCaptureSession? = null
    private var resampler: StreamingPcmResampler? = null
    private var outBuf: ShortArray? = null
    private var timeoutHandle: CaptureTimeoutScheduler.Handle? = null
    private var listener: RecognitionListener? = null

    fun isDirectRunning(): Boolean = directRunning.get() || direct.isRunning()
    fun isFallbackRunning(): Boolean = fallbackRunning.get()
    fun bothPathsRunning(): Boolean = isDirectRunning() && isFallbackRunning()

    fun start(listener: RecognitionListener): CommandCaptureStartResult {
        stop()
        this.listener = listener
        running.set(true)
        resampleInvocations = 0
        recognizerAccepts = 0
        firstPcmLogged = false

        if (direct.isAvailable()) {
            val started = try {
                direct.start(listener)
            } catch (_: Throwable) {
                false
            }
            if (started) {
                path = CommandCapturePath.DIRECT
                directRunning.set(true)
                fallbackRunning.set(false)
                firstPcmLogged = false
                CarfuLog.i(
                    CommandSession.TAG,
                    "COMMAND_CAPTURE path=A rate=${AudioCaptureConfig.MODEL_RATE_HZ} resample=false",
                )
                return CommandCaptureStartResult.Direct
            }
            direct.shutdown()
            directRunning.set(false)
            CarfuLog.w(CommandSession.TAG, "native 16kHz SpeechService start failed, using fallback capture")
        }

        if (direct.isRunning()) {
            direct.shutdown()
            directRunning.set(false)
        }

        val session = fallback.open(AudioCaptureConfig.FALLBACK_RATES)
        if (session == null) {
            running.set(false)
            path = CommandCapturePath.NONE
            val error = IOException("command-capture: no usable microphone rate")
            CarfuLog.e(CommandSession.TAG, "COMMAND_CAPTURE_ERROR no usable microphone rate")
            return CommandCaptureStartResult.Failed(error)
        }

        fallbackSession = session
        val streamResampler = StreamingPcmResampler(session.rateHz)
        resampler = streamResampler
        outBuf = ShortArray(streamResampler.maxOutputLength(session.suggestedReadShorts) + 16)
        path = CommandCapturePath.FALLBACK
        fallbackRunning.set(true)
        directRunning.set(false)

        session.start(object : FallbackPcmConsumer {
            override fun onPcm(samples: ShortArray, length: Int) {
                feedFallbackPcm(samples, length)
            }

            override fun onReadError(error: Exception) {
                if (!running.get()) return
                val sink = listener
                stop()
                sink?.onError(error)
            }
        })

        timeoutHandle = timeoutScheduler.schedule(timeoutMs) {
            if (running.get() && path == CommandCapturePath.FALLBACK) {
                val sink = listener
                stop()
                sink?.onTimeout()
            }
        }

        CarfuLog.i(
            CommandSession.TAG,
            "COMMAND_CAPTURE path=B rate=${session.rateHz} " +
                "bufferSize=${session.bufferBytes} resample=true",
        )
        return CommandCaptureStartResult.Fallback(session.rateHz, session.bufferBytes)
    }

    fun stop() {
        timeoutHandle?.cancel()
        timeoutHandle = null
        running.set(false)

        val session = fallbackSession
        fallbackSession = null
        fallbackRunning.set(false)
        session?.stopAndRelease()

        if (directRunning.get() || direct.isRunning()) {
            direct.stop()
        }
        directRunning.set(false)
        path = CommandCapturePath.NONE
        resampler = null
        outBuf = null
        listener = null
    }

    private fun feedFallbackPcm(samples: ShortArray, length: Int) {
        if (!running.get() || !fallbackRunning.get()) return
        if (direct.isRunning()) {
            CarfuLog.e(CommandSession.TAG, "COMMAND_CAPTURE_ERROR direct and fallback both running")
            stop()
            return
        }
        if (!firstPcmLogged && length > 0) {
            firstPcmLogged = true
            CarfuLog.i(CommandSession.TAG, "FIRST_PCM received=true via=fallback length=$length")
        }
        val streamResampler = resampler ?: return
        var output = outBuf
        val needed = streamResampler.maxOutputLength(length)
        if (output == null || output.size < needed) {
            output = ShortArray(needed + 16)
            outBuf = output
        }
        val nOut = streamResampler.resample(samples, length, output)
        resampleInvocations += 1
        if (nOut <= 0) return
        val isUtteranceEnd = recognizer.acceptWaveForm(output, nOut)
        recognizerAccepts += 1
        val sink = listener ?: return
        if (isUtteranceEnd) {
            sink.onResult(recognizer.resultJson())
        } else {
            sink.onPartialResult(recognizer.partialJson())
        }
    }
}
