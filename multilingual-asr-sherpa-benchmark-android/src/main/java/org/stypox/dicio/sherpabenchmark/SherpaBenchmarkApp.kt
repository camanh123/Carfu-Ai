package org.stypox.dicio.sherpabenchmark

import android.app.Application
import org.stypox.dicio.sherpabenchmark.diagnostics.CrashCapture
import org.stypox.dicio.sherpabenchmark.diagnostics.BenchmarkJournal
import org.stypox.dicio.sherpabenchmark.diagnostics.SessionJournal
import java.io.File

class SherpaBenchmarkApp : Application() {
    lateinit var journal: SessionJournal
        private set
    lateinit var benchJournal: BenchmarkJournal
        private set

    override fun onCreate() {
        super.onCreate()
        journal = SessionJournal(File(filesDir, "session-journal"))
        journal.onLaunch()
        benchJournal = BenchmarkJournal(File(filesDir, "benchmark-journal"))
        benchJournal.onLaunch()
        CrashCapture.install(journal)
        journal.markLifecycle("Application.onCreate")
    }
}
