package org.stypox.dicio.sherpabenchmark.diagnostics

/**
 * Diagnostic-only uncaught exception handler.
 * Persists Java/Kotlin crashes then delegates to the previous handler.
 * Does not swallow fatal exceptions.
 *
 * Native SIGSEGV/SIGABRT are NOT captured by this mechanism.
 */
object CrashCapture {
    @Volatile
    var installed: Boolean = false
        private set

    @Volatile
    var previous: Thread.UncaughtExceptionHandler? = null
        private set

    fun install(journal: SessionJournal) {
        if (installed) return
        previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                journal.recordCrash(thread.name ?: "unknown", error)
            } catch (_: Throwable) {
            }
            val next = previous
            if (next != null) {
                next.uncaughtException(thread, error)
            } else {
                try {
                    error.printStackTrace()
                } catch (_: Throwable) {
                }
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(10)
            }
        }
        installed = true
    }
}
