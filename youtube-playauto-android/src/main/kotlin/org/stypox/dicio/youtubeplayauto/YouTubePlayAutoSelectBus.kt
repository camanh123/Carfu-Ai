package org.stypox.dicio.youtubeplayauto

data class YouTubeSelectJob(
    val query: String,
    val youtubePackage: String,
    val armedAtMs: Long,
    val deadlineMs: Long,
    val session: YouTubePlayAutoSession,
)

object YouTubePlayAutoSelectBus {
    @Volatile
    var job: YouTubeSelectJob? = null

    @Volatile
    var lastOutcome: YouTubeSelectOutcome? = null

    @Volatile
    var lastDiagnostics: YouTubePlayAutoDiagnostics = YouTubePlayAutoDiagnostics()

    fun arm(query: String, youtubePackage: String, nowMs: Long = System.currentTimeMillis()): YouTubeSelectJob {
        val session = YouTubePlayAutoSession(query)
        val next = YouTubeSelectJob(
            query = query,
            youtubePackage = youtubePackage,
            armedAtMs = nowMs,
            deadlineMs = nowMs + 14_000L,
            session = session,
        )
        job = next
        lastDiagnostics = session.diagnostics
        lastOutcome = YouTubeSelectOutcome.Armed(query, youtubePackage)
        return next
    }

    fun diagnostics(): YouTubePlayAutoDiagnostics = job?.session?.diagnostics ?: lastDiagnostics

    fun clear() {
        job?.session?.diagnostics?.let { lastDiagnostics = it }
        job = null
    }
}
