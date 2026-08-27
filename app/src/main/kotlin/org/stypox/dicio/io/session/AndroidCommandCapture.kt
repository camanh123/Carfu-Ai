package org.stypox.dicio.io.session

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

internal class SpeechServiceDirectCapture(
    private var speechService: SpeechService?,
    private val timeoutMs: Int,
) : Direct16kHzCapture {
    private val running = AtomicBoolean(false)

    override fun isAvailable(): Boolean = speechService != null

    override fun start(listener: RecognitionListener): Boolean {
        val service = speechService ?: return false
        return try {
            try {
                service.startListening(listener, timeoutMs)
            } catch (_: Throwable) {
                service.startListening(listener)
            }
            running.set(true)
            true
        } catch (_: Throwable) {
            running.set(false)
            false
        }
    }

    override fun stop() {
        try {
            speechService?.stop()
        } catch (_: Throwable) {
        }
        running.set(false)
    }

    override fun shutdown() {
        stop()
        try {
            speechService?.shutdown()
        } catch (_: Throwable) {
        }
        speechService = null
        running.set(false)
    }

    override fun isRunning(): Boolean = running.get()
}

internal class RecognizerWaveformAdapter(
    private val recognizer: Recognizer,
) : VoskRecognizerAdapter {
    override fun acceptWaveForm(samples: ShortArray, length: Int): Boolean {
        return recognizer.acceptWaveForm(samples, length)
    }

    override fun resultJson(): String = recognizer.result

    override fun partialJson(): String = recognizer.partialResult

    override fun finalJson(): String = recognizer.finalResult
}

internal class CoroutineTimeoutScheduler(
    private val scope: CoroutineScope,
) : CaptureTimeoutScheduler {
    override fun schedule(delayMs: Long, onTimeout: () -> Unit): CaptureTimeoutScheduler.Handle {
        val job: Job = scope.launch {
            delay(delayMs)
            onTimeout()
        }
        return CaptureTimeoutScheduler.Handle { job.cancel() }
    }
}

/**
 * Production fallback capture: probes 48 kHz → 44.1 kHz → 32 kHz → 8 kHz, opens a real
 * [AudioRecord], and reads PCM on a dedicated worker thread.
 */
internal class AndroidFallbackPcmCapture(
    private val probe: CaptureRateProbe = CaptureRateProbe { rateHz ->
        AudioRecord.getMinBufferSize(
            rateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
    },
    private val recordFactory: (rateHz: Int, bufferBytes: Int) -> AudioRecord? =
        Companion::tryOpenRecord,
) : FallbackPcmCapture {
    override fun open(rates: IntArray): FallbackCaptureSession? {
        val opened = AudioCaptureConfig.openFirstFallback(rates, probe) { rateHz, bufferBytes ->
            recordFactory(rateHz, bufferBytes)?.let { record ->
                AndroidFallbackCaptureSession(record, rateHz, bufferBytes)
            }
        }
        return opened
    }

    companion object {
        @SuppressLint("MissingPermission")
        fun tryOpenRecord(rateHz: Int, bufferBytes: Int): AudioRecord? {
            return try {
                val record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    rateHz,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes,
                )
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    record.release()
                    null
                } else {
                    record
                }
            } catch (_: Throwable) {
                null
            }
        }
    }
}

internal class AndroidFallbackCaptureSession(
    private val record: AudioRecord,
    override val rateHz: Int,
    override val bufferBytes: Int,
) : FallbackCaptureSession {
    override val suggestedReadShorts: Int
        get() = (bufferBytes / 2).coerceAtLeast(1)

    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    override fun start(consumer: FallbackPcmConsumer) {
        if (!running.compareAndSet(false, true)) return
        try {
            record.startRecording()
        } catch (t: Throwable) {
            running.set(false)
            consumer.onReadError(IOException("AudioRecord.startRecording failed", t))
            return
        }
        val thread = Thread({
            val inBuf = ShortArray(suggestedReadShorts)
            while (running.get()) {
                val nread = try {
                    record.read(inBuf, 0, inBuf.size)
                } catch (t: Throwable) {
                    if (running.get()) {
                        consumer.onReadError(IOException("AudioRecord.read failed", t))
                    }
                    break
                }
                if (!running.get()) break
                if (nread < 0) {
                    consumer.onReadError(IOException("command-capture: AudioRecord.read=$nread"))
                    break
                }
                if (nread > 0) {
                    consumer.onPcm(inBuf, nread)
                }
            }
        }, WORKER_NAME)
        thread.isDaemon = true
        worker = thread
        thread.start()
    }

    override fun stopAndRelease() {
        running.set(false)
        try {
            record.stop()
        } catch (_: Throwable) {
        }
        try {
            record.release()
        } catch (_: Throwable) {
        }
        val thread = worker
        worker = null
        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join(1_000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    companion object {
        const val WORKER_NAME = "carfu-command-capture"
    }
}
