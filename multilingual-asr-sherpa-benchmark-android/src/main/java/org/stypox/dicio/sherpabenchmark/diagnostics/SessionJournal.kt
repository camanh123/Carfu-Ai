package org.stypox.dicio.sherpabenchmark.diagnostics

import org.stypox.dicio.sherpabenchmark.freeze.BenchmarkLimits
import org.stypox.dicio.sherpabenchmark.freeze.HardFreeze
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class JournalSnapshot(
    val inProgress: Boolean = false,
    val endedAbnormally: Boolean = false,
    val sessionId: String = "",
    val sessionStartEpochMs: Long = 0L,
    val audioDurationMs: Long = 0L,
    val chunkCount: Long = 0L,
    val decodeCount: Int = 0,
    val partialCount: Int = 0,
    val lastSuccessfulPartial: String = "",
    val lastPartialEpochMs: Long = 0L,
    val lastDecodeMs: Long = 0L,
    val maxDecodeMs: Long = 0L,
    val pendingDecodeCount: Int = 0,
    val javaUsedBytes: Long = -1L,
    val nativeHeapBytes: Long = -1L,
    val pssKb: Long = -1L,
    val availMemBytes: Long = -1L,
    val lowMemory: Boolean = false,
    val javaThreadCount: Int = 0,
    val recognizerState: String = "",
    val lastLifecycleEvent: String = "",
    val lastNativeOp: String = "",
    val autoStopReason: String = "",
    val exceptionClass: String = "",
    val exceptionMessage: String = "",
    val exceptionThread: String = "",
    val exceptionStack: String = "",
) {
    fun asPlainText(): String = buildString {
        appendLine("IN_PROGRESS: ${HardFreeze.yesNo(inProgress)}")
        appendLine("ENDED_ABNORMALLY: ${HardFreeze.yesNo(endedAbnormally)}")
        appendLine("SESSION_ID: $sessionId")
        appendLine("SESSION_START: $sessionStartEpochMs")
        appendLine("AUDIO_DURATION_MS: $audioDurationMs")
        appendLine("CHUNK_COUNT: $chunkCount")
        appendLine("DECODE_COUNT: $decodeCount")
        appendLine("PARTIAL_COUNT: $partialCount")
        appendLine("LAST_SUCCESSFUL_PARTIAL: $lastSuccessfulPartial")
        appendLine("LAST_PARTIAL_TIMESTAMP: $lastPartialEpochMs")
        appendLine("LAST_DECODE_MS: $lastDecodeMs")
        appendLine("MAX_DECODE_MS: $maxDecodeMs")
        appendLine("PENDING_DECODE_COUNT: $pendingDecodeCount")
        appendLine("JAVA_HEAP_USED: $javaUsedBytes")
        appendLine("NATIVE_HEAP: $nativeHeapBytes")
        appendLine("PSS_KB: $pssKb")
        appendLine("AVAIL_MEM: $availMemBytes")
        appendLine("LOW_MEMORY: $lowMemory")
        appendLine("JAVA_THREAD_COUNT: $javaThreadCount")
        appendLine("RECOGNIZER_STATE: $recognizerState")
        appendLine("LAST_LIFECYCLE_EVENT: $lastLifecycleEvent")
        appendLine("LAST_NATIVE_OP: $lastNativeOp")
        appendLine("AUTO_STOP_REASON: ${autoStopReason.ifBlank { "(none)" }}")
        if (exceptionClass.isNotBlank()) {
            appendLine("EXCEPTION_CLASS: $exceptionClass")
            appendLine("EXCEPTION_MESSAGE: $exceptionMessage")
            appendLine("EXCEPTION_THREAD: $exceptionThread")
            appendLine("EXCEPTION_STACK:")
            appendLine(exceptionStack)
        }
    }
}

/**
 * Bounded crash-surviving journal. Overwrites a single current file; never appends
 * unbounded logs; never stores microphone audio.
 */
class SessionJournal(private val dir: File) {
    private val lock = ReentrantLock()
    private val currentFile get() = File(dir, CURRENT_NAME)
    private val lastFile get() = File(dir, LAST_NAME)

    @Volatile
    var previousEndedAbnormally: Boolean = false
        private set

    @Volatile
    var lastSessionJournal: JournalSnapshot? = null
        private set

    @Volatile
    var current: JournalSnapshot = JournalSnapshot()
        private set

    fun onLaunch(): JournalSnapshot? = lock.withLock {
        dir.mkdirs()
        val leftover = readFile(currentFile)
        if (leftover != null && leftover.inProgress) {
            previousEndedAbnormally = true
            lastSessionJournal = leftover.copy(endedAbnormally = true, inProgress = false)
            writeFile(lastFile, lastSessionJournal!!)
            currentFile.delete()
            current = JournalSnapshot()
            return lastSessionJournal
        }
        if (leftover != null) {
            lastSessionJournal = leftover
            writeFile(lastFile, leftover)
            currentFile.delete()
        } else {
            lastSessionJournal = readFile(lastFile)
        }
        previousEndedAbnormally = lastSessionJournal?.endedAbnormally == true
        current = JournalSnapshot()
        lastSessionJournal
    }

    fun beginSession(sessionId: String, startEpochMs: Long) {
        lock.withLock {
            current = JournalSnapshot(
                inProgress = true,
                sessionId = sessionId,
                sessionStartEpochMs = startEpochMs,
                lastLifecycleEvent = current.lastLifecycleEvent,
            )
            persistLocked()
        }
    }

    fun update(transform: (JournalSnapshot) -> JournalSnapshot) {
        lock.withLock {
            current = transform(current).let { snap ->
                snap.copy(
                    lastSuccessfulPartial = snap.lastSuccessfulPartial.take(BenchmarkLimits.MAX_JOURNAL_PARTIAL_CHARS),
                    exceptionStack = snap.exceptionStack.take(BenchmarkLimits.MAX_JOURNAL_STACK_CHARS),
                )
            }
            persistLocked()
        }
    }

    fun markLifecycle(event: String) {
        update { it.copy(lastLifecycleEvent = event) }
    }

    fun recordCrash(threadName: String, error: Throwable) {
        lock.withLock {
            current = current.copy(
                inProgress = true,
                endedAbnormally = true,
                exceptionClass = error.javaClass.name,
                exceptionMessage = (error.message ?: "").take(400),
                exceptionThread = threadName,
                exceptionStack = error.stackTraceToString().take(BenchmarkLimits.MAX_JOURNAL_STACK_CHARS),
            )
            persistLocked()
        }
    }

    fun completeNormally() {
        lock.withLock {
            current = current.copy(inProgress = false, endedAbnormally = false)
            persistLocked()
            writeFile(lastFile, current)
            lastSessionJournal = current
            previousEndedAbnormally = false
        }
    }

    fun renderLast(): String {
        val snap = lastSessionJournal
        return if (snap == null) "(no previous session journal)" else snap.asPlainText()
    }

    fun renderCurrent(): String = current.asPlainText()

    private fun persistLocked() {
        dir.mkdirs()
        writeFile(currentFile, current)
    }

    companion object {
        const val CURRENT_NAME: String = "current-session.txt"
        const val LAST_NAME: String = "last-session.txt"

        internal fun writeFile(file: File, snap: JournalSnapshot) {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(encode(snap))
            if (!tmp.renameTo(file)) {
                file.writeText(encode(snap))
                tmp.delete()
            }
        }

        internal fun readFile(file: File): JournalSnapshot? {
            if (!file.isFile) return null
            return decode(file.readText())
        }

        internal fun encode(s: JournalSnapshot): String = buildString {
            fun put(k: String, v: String) {
                append(k).append('=').append(v.replace('\n', ' ').replace('\r', ' ')).append('\n')
            }
            put("inProgress", s.inProgress.toString())
            put("endedAbnormally", s.endedAbnormally.toString())
            put("sessionId", s.sessionId)
            put("sessionStartEpochMs", s.sessionStartEpochMs.toString())
            put("audioDurationMs", s.audioDurationMs.toString())
            put("chunkCount", s.chunkCount.toString())
            put("decodeCount", s.decodeCount.toString())
            put("partialCount", s.partialCount.toString())
            put("lastSuccessfulPartial", s.lastSuccessfulPartial)
            put("lastPartialEpochMs", s.lastPartialEpochMs.toString())
            put("lastDecodeMs", s.lastDecodeMs.toString())
            put("maxDecodeMs", s.maxDecodeMs.toString())
            put("pendingDecodeCount", s.pendingDecodeCount.toString())
            put("javaUsedBytes", s.javaUsedBytes.toString())
            put("nativeHeapBytes", s.nativeHeapBytes.toString())
            put("pssKb", s.pssKb.toString())
            put("availMemBytes", s.availMemBytes.toString())
            put("lowMemory", s.lowMemory.toString())
            put("javaThreadCount", s.javaThreadCount.toString())
            put("recognizerState", s.recognizerState)
            put("lastLifecycleEvent", s.lastLifecycleEvent)
            put("lastNativeOp", s.lastNativeOp)
            put("autoStopReason", s.autoStopReason)
            put("exceptionClass", s.exceptionClass)
            put("exceptionMessage", s.exceptionMessage)
            put("exceptionThread", s.exceptionThread)
            put("exceptionStack", s.exceptionStack)
        }

        internal fun decode(text: String): JournalSnapshot {
            val map = LinkedHashMap<String, String>()
            text.lineSequence().forEach { line ->
                val eq = line.indexOf('=')
                if (eq > 0) map[line.substring(0, eq)] = line.substring(eq + 1)
            }
            fun s(k: String) = map[k] ?: ""
            fun l(k: String) = s(k).toLongOrNull() ?: 0L
            fun i(k: String) = s(k).toIntOrNull() ?: 0
            fun b(k: String) = s(k).toBoolean()
            return JournalSnapshot(
                inProgress = b("inProgress"),
                endedAbnormally = b("endedAbnormally"),
                sessionId = s("sessionId"),
                sessionStartEpochMs = l("sessionStartEpochMs"),
                audioDurationMs = l("audioDurationMs"),
                chunkCount = l("chunkCount"),
                decodeCount = i("decodeCount"),
                partialCount = i("partialCount"),
                lastSuccessfulPartial = s("lastSuccessfulPartial"),
                lastPartialEpochMs = l("lastPartialEpochMs"),
                lastDecodeMs = l("lastDecodeMs"),
                maxDecodeMs = l("maxDecodeMs"),
                pendingDecodeCount = i("pendingDecodeCount"),
                javaUsedBytes = l("javaUsedBytes"),
                nativeHeapBytes = l("nativeHeapBytes"),
                pssKb = l("pssKb"),
                availMemBytes = l("availMemBytes"),
                lowMemory = b("lowMemory"),
                javaThreadCount = i("javaThreadCount"),
                recognizerState = s("recognizerState"),
                lastLifecycleEvent = s("lastLifecycleEvent"),
                lastNativeOp = s("lastNativeOp"),
                autoStopReason = s("autoStopReason"),
                exceptionClass = s("exceptionClass"),
                exceptionMessage = s("exceptionMessage"),
                exceptionThread = s("exceptionThread"),
                exceptionStack = s("exceptionStack"),
            )
        }
    }
}
