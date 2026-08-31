package org.stypox.dicio.io.session

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide tap on the single CARFU [android.media.AudioRecord].
 * [org.stypox.dicio.io.wake.WakeService] owns the physical recorder; command STT
 * attaches a consumer here instead of constructing Vosk [org.vosk.android.SpeechService].
 *
 * The wake-service audio buffer is reused, so pending frames are copied.
 */
object CarfuPcmHub {
    const val PRE_ATTACH_FRAMES = 8

    private val recording = AtomicBoolean(false)
    private val commandConsumer = AtomicReference<FallbackPcmConsumer?>(null)
    private val pendingLock = Any()
    private val pending = ArrayDeque<ShortArray>()
    private val droppedWithoutConsumer = AtomicInteger(0)
    private val dropLogged = AtomicBoolean(false)

    fun isRecording(): Boolean = recording.get()

    fun hasCommandConsumer(): Boolean = commandConsumer.get() != null

    fun markRecording(active: Boolean) {
        recording.set(active)
        if (!active) {
            commandConsumer.set(null)
            clearPending()
        }
    }

    fun attachCommandConsumer(consumer: FallbackPcmConsumer): Boolean {
        if (!recording.get()) return false
        commandConsumer.set(consumer)
        dropLogged.set(false)
        flushPending(consumer)
        return true
    }

    fun detachCommandConsumer() {
        commandConsumer.set(null)
        clearPending()
    }

    fun feedCommand(samples: ShortArray, length: Int) {
        if (length <= 0) return
        val consumer = commandConsumer.get()
        if (consumer != null) {
            flushPending(consumer)
            consumer.onPcm(samples, length)
            return
        }
        val dropped = droppedWithoutConsumer.incrementAndGet()
        synchronized(pendingLock) {
            if (pending.size >= PRE_ATTACH_FRAMES) {
                pending.removeFirst()
            }
            pending.addLast(samples.copyOf(length))
        }
        if (dropLogged.compareAndSet(false, true)) {
            CarfuLog.w(
                CommandSession.TAG,
                "COMMAND_PCM_DROPPED no_consumer=true buffered=true dropped=$dropped",
            )
        }
    }

    fun notifyCommandError(error: Exception) {
        commandConsumer.get()?.onReadError(error)
    }

    fun pendingFrameCount(): Int = synchronized(pendingLock) { pending.size }

    fun droppedWithoutConsumerCount(): Int = droppedWithoutConsumer.get()

    fun resetForTests() {
        recording.set(false)
        commandConsumer.set(null)
        droppedWithoutConsumer.set(0)
        dropLogged.set(false)
        clearPending()
    }

    private fun flushPending(consumer: FallbackPcmConsumer) {
        val frames: List<ShortArray> = synchronized(pendingLock) {
            if (pending.isEmpty()) return
            val copy = pending.toList()
            pending.clear()
            copy
        }
        for (frame in frames) {
            consumer.onPcm(frame, frame.size)
        }
    }

    private fun clearPending() {
        synchronized(pendingLock) { pending.clear() }
    }
}
