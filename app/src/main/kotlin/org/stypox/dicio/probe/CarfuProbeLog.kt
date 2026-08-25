package org.stypox.dicio.probe

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory log bus for the CARFU capability probe. The Activity displays lines,
 * while [CarfuAudioProbeService] can keep appending after the UI is backgrounded.
 */
object CarfuProbeLog {
    private const val TAG = "CarfuProbe"
    private const val MAX_LINES = 2000

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lines = ArrayDeque<String>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    @Synchronized
    fun append(message: String) {
        val line = "[${timeFormat.format(Date())}] $message"
        Log.i(TAG, message)
        lines.addLast(line)
        while (lines.size > MAX_LINES) {
            lines.removeFirst()
        }
        val snapshot = line
        mainHandler.post {
            listeners.forEach { listener -> listener(snapshot) }
        }
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun clear() {
        lines.clear()
    }

    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }
}
