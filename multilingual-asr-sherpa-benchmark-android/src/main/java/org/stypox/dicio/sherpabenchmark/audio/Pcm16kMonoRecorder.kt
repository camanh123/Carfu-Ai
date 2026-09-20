package org.stypox.dicio.sherpabenchmark.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class AudioCaptureException(message: String) : RuntimeException(message)

/**
 * Standalone 16 kHz mono PCM capture for Phase 3B.1.
 * Manual START/STOP only. No VAD, no wake word, no background listening.
 */
class Pcm16kMonoRecorder {
    private val recording = AtomicBoolean(false)
    @Volatile
    private var worker: Thread? = null
    private val pcm = ByteArrayOutputStream()
    private val lock = Any()

    private val chunks = AtomicLong(0)

    val chunkCount: Long get() = chunks.get()

    @Volatile
    var firstChunkElapsedMs: Long = -1L
        private set

    private var startNs: Long = 0L

    val isRecording: Boolean get() = recording.get()

    @SuppressLint("MissingPermission")
    fun start() {
        if (!recording.compareAndSet(false, true)) {
            throw AudioCaptureException("double START: already recording")
        }
        synchronized(lock) {
            pcm.reset()
        }
        firstChunkElapsedMs = -1L
        chunks.set(0L)
        startNs = System.nanoTime()

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            recording.set(false)
            throw AudioCaptureException("AudioRecord.getMinBufferSize failed: $minBuf")
        }
        val bufSize = minBuf * 4
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize,
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            recording.set(false)
            throw AudioCaptureException("AudioRecord failed to initialize")
        }

        worker = thread(name = "sherpa-mic", start = true) {
            val buf = ByteArray(bufSize)
            try {
                recorder.startRecording()
                while (recording.get()) {
                    val n = recorder.read(buf, 0, buf.size)
                    if (n > 0) {
                        if (firstChunkElapsedMs < 0L) {
                            firstChunkElapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                        }
                        chunks.incrementAndGet()
                        synchronized(lock) {
                            pcm.write(buf, 0, n)
                        }
                    } else if (n < 0) {
                        break
                    }
                }
            } finally {
                try {
                    recorder.stop()
                } catch (_: Throwable) {
                }
                recorder.release()
            }
        }
    }

    fun snapshotPcm16Bytes(): ByteArray = synchronized(lock) { pcm.toByteArray() }

    fun snapshotFloatSamples(): FloatArray {
        return pcm16leToFloat32(snapshotPcm16Bytes())
    }

    fun requestStop() {
        recording.set(false)
    }

    fun awaitStopped(timeoutMs: Long = 5_000) {
        recording.set(false)
        worker?.join(timeoutMs)
        worker = null
    }

    fun stopAndFloatSamples(): FloatArray {
        awaitStopped()
        return snapshotFloatSamples()
    }

    fun release() {
        recording.set(false)
        try {
            worker?.join(1_000)
        } catch (_: Throwable) {
        }
        worker = null
        synchronized(lock) { pcm.reset() }
    }

    fun recordedDurationMs(): Long {
        val nBytes = synchronized(lock) { pcm.size() }
        val nSamples = nBytes / BYTES_PER_SAMPLE
        return (nSamples * 1000L) / SAMPLE_RATE_HZ
    }

    companion object {
        const val SAMPLE_RATE_HZ: Int = 16_000
        const val CHANNELS: Int = 1
        const val BYTES_PER_SAMPLE: Int = 2
        const val FORMAT: String = "16 kHz mono PCM16 LE → float32 [-1,1]"

        fun pcm16leToFloat32(bytes: ByteArray): FloatArray {
            val nSamples = bytes.size / BYTES_PER_SAMPLE
            val out = FloatArray(nSamples)
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until nSamples) {
                out[i] = bb.short / 32768.0f
            }
            return out
        }

        fun durationMs(sampleCount: Int): Long =
            if (sampleCount <= 0) 0L else (sampleCount * 1000L) / SAMPLE_RATE_HZ
    }
}
