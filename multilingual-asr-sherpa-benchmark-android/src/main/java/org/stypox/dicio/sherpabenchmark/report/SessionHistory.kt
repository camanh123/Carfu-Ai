package org.stypox.dicio.sherpabenchmark.report

class SessionHistory(private val limit: Int = DEFAULT_LIMIT) {
    private val items = ArrayDeque<BenchmarkSession>()

    val size: Int get() = items.size

    fun add(session: BenchmarkSession) {
        items.addFirst(session)
        while (items.size > limit) {
            items.removeLast()
        }
    }

    fun snapshot(): List<BenchmarkSession> = items.toList()

    fun asPlainText(): String {
        if (items.isEmpty()) return "(no sessions yet)"
        return items.joinToString("\n") { it.shortHistoryLine() }
    }

    companion object {
        const val DEFAULT_LIMIT: Int = 20
    }
}
