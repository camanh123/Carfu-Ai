package org.stypox.dicio.io.session

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bounded in-memory diagnostics for the UIS7862 test without desktop adb.
 * Never stores raw audio. Copyable from the Chẩn đoán screen.
 */
object CarfuDiag {
    const val TAG_WAKE = "CarfuWake"
    const val TAG_COMMAND = "CarfuCommand"
    const val TAG_QUICK = "QuickAction"
    const val MAX_PER_TAG = 80

    private val lock = Any()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val wake = ArrayDeque<String>()
    private val command = ArrayDeque<String>()
    private val quick = ArrayDeque<String>()

    fun wake(message: String) {
        append(TAG_WAKE, wake, message)
        logcat(TAG_WAKE, message)
    }

    fun command(message: String) {
        append(TAG_COMMAND, command, message)
        logcat(TAG_COMMAND, message)
    }

    fun quick(message: String) {
        append(TAG_QUICK, quick, message)
        logcat(TAG_QUICK, message)
    }

    fun appendForTag(tag: String, message: String) {
        when (tag) {
            TAG_WAKE -> wake(message)
            TAG_COMMAND -> command(message)
            TAG_QUICK -> quick(message)
        }
    }

    fun copyableLog(): String = synchronized(lock) {
        buildString {
            appendLine("=== $TAG_WAKE ===")
            if (wake.isEmpty()) appendLine("(empty)") else wake.forEach { appendLine(it) }
            appendLine("=== $TAG_COMMAND ===")
            if (command.isEmpty()) appendLine("(empty)") else command.forEach { appendLine(it) }
            appendLine("=== $TAG_QUICK ===")
            if (quick.isEmpty()) appendLine("(empty)") else quick.forEach { appendLine(it) }
        }
    }

    fun recent(tag: String): List<String> = synchronized(lock) {
        when (tag) {
            TAG_WAKE -> wake.toList()
            TAG_COMMAND -> command.toList()
            TAG_QUICK -> quick.toList()
            else -> emptyList()
        }
    }

    fun clear() {
        synchronized(lock) {
            wake.clear()
            command.clear()
            quick.clear()
        }
    }

    internal fun record(tag: String, message: String) {
        when (tag) {
            TAG_WAKE -> append(TAG_WAKE, wake, message)
            TAG_COMMAND -> append(TAG_COMMAND, command, message)
            TAG_QUICK -> append(TAG_QUICK, quick, message)
        }
    }

    private fun append(tag: String, deque: ArrayDeque<String>, message: String) {
        val line = "[${timeFormat.format(Date())}] $tag $message"
        synchronized(lock) {
            deque.addLast(line)
            while (deque.size > MAX_PER_TAG) {
                deque.removeFirst()
            }
        }
    }

    private fun logcat(tag: String, message: String) {
        try {
            android.util.Log.i(tag, message)
        } catch (_: Throwable) {
        }
    }
}
