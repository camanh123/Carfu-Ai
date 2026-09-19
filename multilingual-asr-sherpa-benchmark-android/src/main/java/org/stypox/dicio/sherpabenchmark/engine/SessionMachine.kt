package org.stypox.dicio.sherpabenchmark.engine

enum class BenchState {
    IDLE,
    RECORDING,
    PROCESSING,
    COMPLETE,
    ERROR,
}

class SessionIsolationException(message: String) : IllegalStateException(message)

data class IsolatedSession(
    val sessionId: String,
    val sequence: Int,
    val threadCount: Int,
    val createdEpochMs: Long,
    val audioStartEpochMs: Long,
) {
    var latestPartial: String = ""
        private set
    var finalRawTranscript: String = ""
        private set
    val partials: MutableList<PartialEvent> = ArrayList()
    var error: String = ""
    var stopEpochMs: Long = -1L
    var finalizedEpochMs: Long = -1L
    var audioDurationMs: Long = 0L
    var firstAudioChunkMs: Long = -1L
    var timeToFirstNonEmptyPartialMs: Long? = null
    var stopToFinalMs: Long = -1L
    var totalAsrComputeMs: Long = 0L
    var lastPartialEpochMs: Long = -1L
    var recognizerInitMs: Long = -1L
    var autoStopReason: String = ""
    var totalPartialCount: Int = 0

    fun replaceLatestPartial(event: PartialEvent) {
        latestPartial = event.text
        partials += event
        while (partials.size > org.stypox.dicio.sherpabenchmark.freeze.BenchmarkLimits.MAX_PARTIAL_EVENTS) {
            partials.removeAt(0)
        }
        totalPartialCount += 1
        lastPartialEpochMs = event.wallClockEpochMs
        if (timeToFirstNonEmptyPartialMs == null && event.text.isNotEmpty()) {
            timeToFirstNonEmptyPartialMs = event.sessionElapsedMs
        }
    }

    fun setFinalRaw(text: String) {
        finalRawTranscript = text
    }
}

/**
 * START creates a brand-new isolated session. Previous sessions are never mutated.
 * Double START / double STOP are rejected.
 */
class SessionMachine(
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    var state: BenchState = BenchState.IDLE
        private set
    var current: IsolatedSession? = null
        private set
    var lastReleasedSessionId: String? = null
        private set
    private var sequence: Int = 0
    private val archived = ArrayList<IsolatedSession>()

    fun canStart(): Boolean =
        state == BenchState.IDLE || state == BenchState.COMPLETE || state == BenchState.ERROR

    fun canStop(): Boolean = state == BenchState.RECORDING

    fun start(threadCount: Int): IsolatedSession {
        if (!canStart()) {
            throw SessionIsolationException("double START rejected; state=$state")
        }
        current?.let { archived += it }
        while (archived.size > SessionHistoryLimit) archived.removeAt(0)
        sequence += 1
        val now = clock()
        val session = IsolatedSession(
            sessionId = "p3b1-$sequence-$now",
            sequence = sequence,
            threadCount = threadCount,
            createdEpochMs = now,
            audioStartEpochMs = now,
        )
        current = session
        state = BenchState.RECORDING
        return session
    }

    fun requestStop(): IsolatedSession {
        if (!canStop()) {
            throw SessionIsolationException("double STOP rejected; state=$state")
        }
        val session = current ?: throw SessionIsolationException("STOP with no session")
        session.stopEpochMs = clock()
        state = BenchState.PROCESSING
        return session
    }

    fun complete(raw: String, computeMs: Long, audioMs: Long, stopToFinalMs: Long) {
        val session = current ?: throw SessionIsolationException("complete with no session")
        session.setFinalRaw(raw)
        session.totalAsrComputeMs = computeMs
        session.audioDurationMs = audioMs
        session.stopToFinalMs = stopToFinalMs
        session.finalizedEpochMs = clock()
        state = BenchState.COMPLETE
    }

    fun fail(message: String) {
        current?.error = message
        state = BenchState.ERROR
    }

    fun cleanup() {
        lastReleasedSessionId = current?.sessionId
        current = null
        state = BenchState.IDLE
        archived.clear()
        // Do not reset sequence: session IDs must never be reused after release.
    }

    fun archivedSessions(): List<IsolatedSession> = archived.toList()

    companion object {
        private const val SessionHistoryLimit: Int = 20
    }
}
