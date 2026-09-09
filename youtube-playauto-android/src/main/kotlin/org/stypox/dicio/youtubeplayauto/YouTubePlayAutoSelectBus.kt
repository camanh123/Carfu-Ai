package org.stypox.dicio.youtubeplayauto

/**
 * One-shot in-app select job. Cleared after a successful click so PlayAuto never
 * double-taps a second result.
 */
data class YouTubeSelectJob(
    val query: String,
    val youtubePackage: String,
    val armedAtMs: Long,
    val deadlineMs: Long,
)

object YouTubePlayAutoSelectBus {
    @Volatile
    var job: YouTubeSelectJob? = null

    @Volatile
    var lastOutcome: YouTubeSelectOutcome? = null

    fun arm(query: String, youtubePackage: String, nowMs: Long = System.currentTimeMillis()): YouTubeSelectJob {
        val next = YouTubeSelectJob(
            query = query,
            youtubePackage = youtubePackage,
            armedAtMs = nowMs,
            deadlineMs = nowMs + 12_000L,
        )
        job = next
        lastOutcome = YouTubeSelectOutcome.Armed(query, youtubePackage)
        return next
    }

    fun clear() {
        job = null
    }
}
