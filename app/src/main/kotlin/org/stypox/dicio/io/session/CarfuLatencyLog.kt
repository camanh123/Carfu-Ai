package org.stypox.dicio.io.session

/**
 * MODE / command-STT latency marks. Uses injectable [nowMs] (production:
 * [android.os.SystemClock.elapsedRealtime]). Never logs raw audio or private speech.
 */
object CarfuLatencyLog {
    enum class Mark {
        MODE_RECEIVED,
        SESSION_ACCEPTED,
        HUB_RELEASED,
        ACK_START,
        ACK_DONE,
        STT_START,
        READY_FOR_SPEECH,
        BEGINNING_OF_SPEECH,
        PARTIAL_RESULT,
        FINAL_RESULT,
        ROUTER_START,
        COMMAND_MATCH,
        COMMAND_EXECUTE,
        SESSION_END,
        /** @deprecated use [ACK_START] */
        TTS_SPEAK_REQUESTED,
        /** @deprecated use [ACK_START] */
        TTS_ON_START,
        /** @deprecated use [ACK_DONE] */
        TTS_ON_DONE,
        /** @deprecated use [STT_START] */
        SR_START_LISTENING,
        /** @deprecated use [READY_FOR_SPEECH] */
        SR_READY,
        /** @deprecated use [PARTIAL_RESULT] */
        FIRST_PARTIAL,
        /** @deprecated use [FINAL_RESULT] */
        FINAL_OR_ERROR,
        /** @deprecated use [COMMAND_EXECUTE] */
        ACTION_COMPLETE,
        /** @deprecated use [MODE_RECEIVED] */
        MODE_INTENT,
    }

    const val TAG = "CarfuLatency"

    var nowMs: () -> Long = { System.currentTimeMillis() }

    @Volatile
    var sessionId: Long = 0L
        private set

    private val marks = LinkedHashMap<Mark, Long>()
    private val lock = Any()
    private var firstPartialLogged = false

    fun onModeIntent() {
        synchronized(lock) {
            sessionId = 0L
            marks.clear()
            firstPartialLogged = false
            val t = nowMs()
            marks[Mark.MODE_RECEIVED] = t
            marks[Mark.MODE_INTENT] = t
        }
        logMark(Mark.MODE_RECEIVED)
    }

    fun bindSession(id: Long) {
        synchronized(lock) {
            sessionId = id
        }
    }

    fun mark(mark: Mark, atMs: Long = nowMs()) {
        val canonical = canonicalMark(mark)
        val isFirstPartial: Boolean
        synchronized(lock) {
            if (canonical == Mark.PARTIAL_RESULT) {
                if (firstPartialLogged) return
                firstPartialLogged = true
                isFirstPartial = true
            } else {
                isFirstPartial = false
            }
            marks[canonical] = atMs
            // Keep legacy aliases in sync for tests that still read them.
            legacyAlias(canonical)?.let { marks[it] = atMs }
        }
        if (canonical == Mark.PARTIAL_RESULT && !isFirstPartial) return
        logMark(canonical)
    }

    fun logSessionEvent(
        event: String,
        details: String = "",
    ) {
        val sid: Long
        val fromIntent: Long
        synchronized(lock) {
            sid = sessionId
            val intent = marks[Mark.MODE_RECEIVED] ?: marks[Mark.MODE_INTENT] ?: 0L
            val t = nowMs()
            fromIntent = if (intent == 0L) -1L else t - intent
        }
        val suffix = if (details.isEmpty()) "" else " $details"
        CarfuLog.i(TAG, "SESSION session=$sid event=$event +${fromIntent}ms$suffix")
    }

    fun millis(mark: Mark): Long {
        synchronized(lock) {
            return marks[canonicalMark(mark)] ?: 0L
        }
    }

    fun deltaMs(from: Mark, to: Mark): Long {
        synchronized(lock) {
            val a = marks[canonicalMark(from)] ?: return -1L
            val b = marks[canonicalMark(to)] ?: return -1L
            return b - a
        }
    }

    fun resetForTests() {
        synchronized(lock) {
            nowMs = { System.currentTimeMillis() }
            sessionId = 0L
            marks.clear()
            firstPartialLogged = false
        }
    }

    private fun canonicalMark(mark: Mark): Mark = when (mark) {
        Mark.MODE_INTENT -> Mark.MODE_RECEIVED
        Mark.TTS_SPEAK_REQUESTED, Mark.TTS_ON_START -> Mark.ACK_START
        Mark.TTS_ON_DONE -> Mark.ACK_DONE
        Mark.SR_START_LISTENING -> Mark.STT_START
        Mark.SR_READY -> Mark.READY_FOR_SPEECH
        Mark.FIRST_PARTIAL -> Mark.PARTIAL_RESULT
        Mark.FINAL_OR_ERROR -> Mark.FINAL_RESULT
        Mark.ACTION_COMPLETE -> Mark.COMMAND_EXECUTE
        else -> mark
    }

    private fun legacyAlias(canonical: Mark): Mark? = when (canonical) {
        Mark.MODE_RECEIVED -> Mark.MODE_INTENT
        Mark.ACK_START -> Mark.TTS_SPEAK_REQUESTED
        Mark.ACK_DONE -> Mark.TTS_ON_DONE
        Mark.STT_START -> Mark.SR_START_LISTENING
        Mark.READY_FOR_SPEECH -> Mark.SR_READY
        Mark.PARTIAL_RESULT -> Mark.FIRST_PARTIAL
        Mark.FINAL_RESULT -> Mark.FINAL_OR_ERROR
        Mark.COMMAND_EXECUTE -> Mark.ACTION_COMPLETE
        else -> null
    }

    private fun logMark(mark: Mark) {
        val sid: Long
        val t: Long
        val fromIntent: Long
        synchronized(lock) {
            sid = sessionId
            t = marks[mark] ?: 0L
            val intent = marks[Mark.MODE_RECEIVED] ?: 0L
            fromIntent = if (intent == 0L) -1L else t - intent
        }
        val budget = if (mark == Mark.ACK_START && fromIntent >= 0L) {
            " within_ack_budget=${fromIntent <= 1000L}"
        } else {
            ""
        }
        CarfuLog.i(
            TAG,
            "LATENCY session=$sid mark=$mark t_ms=$t delta_from_MODE_ms=$fromIntent$budget",
        )
    }
}
