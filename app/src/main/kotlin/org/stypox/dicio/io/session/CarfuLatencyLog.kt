package org.stypox.dicio.io.session

/**
 * MODE / command-STT latency marks. Uses injectable [nowMs] (production:
 * [android.os.SystemClock.elapsedRealtime]). Never logs raw audio or private speech.
 */
object CarfuLatencyLog {
    enum class Mark {
        MODE_INTENT,
        SESSION_ACCEPTED,
        HUB_RELEASED,
        TTS_SPEAK_REQUESTED,
        TTS_ON_START,
        TTS_ON_DONE,
        SR_START_LISTENING,
        SR_READY,
        FIRST_PARTIAL,
        FINAL_OR_ERROR,
        ROUTER_START,
        ACTION_COMPLETE,
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
            marks[Mark.MODE_INTENT] = nowMs()
        }
        logMark(Mark.MODE_INTENT)
    }

    fun bindSession(id: Long) {
        synchronized(lock) {
            sessionId = id
        }
    }

    fun mark(mark: Mark, atMs: Long = nowMs()) {
        val isFirstPartial: Boolean
        synchronized(lock) {
            if (mark == Mark.FIRST_PARTIAL) {
                if (firstPartialLogged) return
                firstPartialLogged = true
                isFirstPartial = true
            } else {
                isFirstPartial = false
            }
            marks[mark] = atMs
        }
        if (mark == Mark.FIRST_PARTIAL && !isFirstPartial) return
        logMark(mark)
    }

    fun millis(mark: Mark): Long {
        synchronized(lock) {
            return marks[mark] ?: 0L
        }
    }

    fun deltaMs(from: Mark, to: Mark): Long {
        synchronized(lock) {
            val a = marks[from] ?: return -1L
            val b = marks[to] ?: return -1L
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

    private fun logMark(mark: Mark) {
        val sid: Long
        val t: Long
        val fromIntent: Long
        synchronized(lock) {
            sid = sessionId
            t = marks[mark] ?: 0L
            val intent = marks[Mark.MODE_INTENT] ?: 0L
            fromIntent = if (intent == 0L) -1L else t - intent
        }
        val budget = if (mark == Mark.TTS_ON_START && fromIntent >= 0L) {
            " within_tts_budget=${fromIntent <= 500L}"
        } else {
            ""
        }
        CarfuLog.i(
            TAG,
            "LATENCY session=$sid mark=$mark t_ms=$t delta_from_MODE_INTENT_ms=$fromIntent$budget",
        )
    }
}
