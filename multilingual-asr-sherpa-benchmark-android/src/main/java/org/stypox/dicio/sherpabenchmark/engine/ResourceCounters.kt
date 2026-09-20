package org.stypox.dicio.sherpabenchmark.engine

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Native/Java resource accounting for the diagnostic app.
 * Recognizer may persist across soak iterations; that is reported, not a leak.
 */
class ResourceCounters {
    val recognizerCreated = AtomicInteger(0)
    val recognizerReleased = AtomicInteger(0)
    val streamCreated = AtomicInteger(0)
    val streamReleased = AtomicInteger(0)
    val activeStreams = AtomicInteger(0)
    val activeDecode = AtomicInteger(0)
    private val decodeHeld = AtomicBoolean(false)

    fun snapshot(): ResourceSnapshot = ResourceSnapshot(
        recognizerCreated = recognizerCreated.get(),
        recognizerReleased = recognizerReleased.get(),
        streamCreated = streamCreated.get(),
        streamReleased = streamReleased.get(),
        activeStreams = activeStreams.get(),
        activeDecode = activeDecode.get(),
        pendingDecode = 0,
    )

    fun tryBeginDecode(): Boolean {
        if (!decodeHeld.compareAndSet(false, true)) return false
        activeDecode.set(1)
        return true
    }

    fun endDecode() {
        activeDecode.set(0)
        decodeHeld.set(false)
    }

    fun onStreamCreated() {
        streamCreated.incrementAndGet()
        activeStreams.incrementAndGet()
    }

    fun onStreamReleased() {
        streamReleased.incrementAndGet()
        activeStreams.updateAndGet { cur -> maxOf(0, cur - 1) }
    }

    fun onRecognizerCreated() {
        recognizerCreated.incrementAndGet()
    }

    fun onRecognizerReleased() {
        recognizerReleased.incrementAndGet()
    }
}

data class ResourceSnapshot(
    val recognizerCreated: Int,
    val recognizerReleased: Int,
    val streamCreated: Int,
    val streamReleased: Int,
    val activeStreams: Int,
    val activeDecode: Int,
    val pendingDecode: Int,
)
