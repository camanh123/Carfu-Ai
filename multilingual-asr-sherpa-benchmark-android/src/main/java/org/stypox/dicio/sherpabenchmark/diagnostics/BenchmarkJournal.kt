package org.stypox.dicio.sherpabenchmark.diagnostics

import org.stypox.dicio.sherpabenchmark.engine.SoakProgress
import org.stypox.dicio.sherpabenchmark.freeze.FixedAudioLimits
import org.stypox.dicio.sherpabenchmark.freeze.HardFreeze
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Bounded persistent store for Phase 3B.1.2 controlled benchmarks.
 * Detects abnormal soak/thread-bench termination via leftover in-progress marker.
 */
class BenchmarkJournal(private val dir: File) {
    private val lock = ReentrantLock()
    private val progressFile get() = File(dir, PROGRESS_NAME)
    private val lastFile get() = File(dir, LAST_NAME)

    @Volatile
    var previousEndedAbnormally: Boolean = false
        private set

    @Volatile
    var lastCompletedIteration: Int = -1
        private set

    @Volatile
    var lastReport: String = ""
        private set

    fun onLaunch() {
        lock.withLock {
            dir.mkdirs()
            val leftover = if (progressFile.isFile) parseProgress(progressFile.readText()) else null
            if (leftover != null && leftover.inProgress) {
                previousEndedAbnormally = true
                lastCompletedIteration = leftover.lastCompletedIteration
            } else {
                previousEndedAbnormally = false
                lastCompletedIteration = leftover?.lastCompletedIteration ?: -1
            }
            lastReport = if (lastFile.isFile) {
                lastFile.readText().take(FixedAudioLimits.MAX_JOURNAL_CHARS)
            } else {
                ""
            }
            if (leftover != null && leftover.inProgress) {
                progressFile.writeText(
                    encodeProgress(leftover.copy(inProgress = false, cancelled = true)),
                )
            }
        }
    }

    fun markProgress(progress: SoakProgress) {
        lock.withLock {
            dir.mkdirs()
            progressFile.writeText(encodeProgress(progress))
            lastCompletedIteration = progress.lastCompletedIteration
        }
    }

    fun complete(report: String) {
        lock.withLock {
            dir.mkdirs()
            val clipped = report.take(FixedAudioLimits.MAX_REPORT_CHARS)
            val tmp = File(dir, LAST_NAME + ".tmp")
            tmp.writeText(clipped)
            if (!tmp.renameTo(lastFile)) {
                lastFile.writeText(clipped)
                tmp.delete()
            }
            lastReport = clipped.take(FixedAudioLimits.MAX_JOURNAL_CHARS)
            progressFile.writeText(
                encodeProgress(
                    SoakProgress(
                        threads = 0,
                        plannedIterations = 0,
                        lastCompletedIteration = lastCompletedIteration,
                        inProgress = false,
                        cancelled = false,
                    ),
                ),
            )
            previousEndedAbnormally = false
        }
    }

    fun renderPreviousBanner(): String = buildString {
        append("PREVIOUS BENCHMARK ENDED ABNORMALLY: ")
        append(HardFreeze.yesNo(previousEndedAbnormally))
        append('\n')
        append("LAST COMPLETED ITERATION: ")
        append(if (lastCompletedIteration >= 0) lastCompletedIteration.toString() else "(none)")
    }

    companion object {
        const val PROGRESS_NAME: String = "benchmark-in-progress.txt"
        const val LAST_NAME: String = "last-benchmark-report.txt"

        internal fun encodeProgress(p: SoakProgress): String = buildString {
            append("inProgress=").append(p.inProgress).append('\n')
            append("cancelled=").append(p.cancelled).append('\n')
            append("threads=").append(p.threads).append('\n')
            append("plannedIterations=").append(p.plannedIterations).append('\n')
            append("lastCompletedIteration=").append(p.lastCompletedIteration).append('\n')
        }

        internal fun parseProgress(text: String): SoakProgress {
            val map = LinkedHashMap<String, String>()
            text.lineSequence().forEach { line ->
                val eq = line.indexOf('=')
                if (eq > 0) map[line.substring(0, eq)] = line.substring(eq + 1)
            }
            fun s(k: String) = map[k] ?: ""
            return SoakProgress(
                threads = s("threads").toIntOrNull() ?: 0,
                plannedIterations = s("plannedIterations").toIntOrNull() ?: 0,
                lastCompletedIteration = s("lastCompletedIteration").toIntOrNull() ?: -1,
                inProgress = s("inProgress").toBoolean(),
                cancelled = s("cancelled").toBoolean(),
            )
        }
    }
}
