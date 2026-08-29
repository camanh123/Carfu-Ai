package org.stypox.dicio.io.session

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide tap on the single CARFU [android.media.AudioRecord].
 * [org.stypox.dicio.io.wake.WakeService] owns the physical recorder; command STT
 * attaches a consumer here instead of constructing Vosk [org.vosk.android.SpeechService].
 */
object CarfuPcmHub {
    private val recording = AtomicBoolean(false)
    private val commandConsumer = AtomicReference<FallbackPcmConsumer?>(null)

    fun isRecording(): Boolean = recording.get()

    fun markRecording(active: Boolean) {
        recording.set(active)
    }

    fun attachCommandConsumer(consumer: FallbackPcmConsumer): Boolean {
        if (!recording.get()) return false
        commandConsumer.set(consumer)
        return true
    }

    fun detachCommandConsumer() {
        commandConsumer.set(null)
    }

    fun feedCommand(samples: ShortArray, length: Int) {
        commandConsumer.get()?.onPcm(samples, length)
    }

    fun notifyCommandError(error: Exception) {
        commandConsumer.get()?.onReadError(error)
    }

    fun resetForTests() {
        recording.set(false)
        commandConsumer.set(null)
    }
}
