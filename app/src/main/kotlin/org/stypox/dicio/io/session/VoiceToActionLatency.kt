package org.stypox.dicio.io.session

/**
 * Phase 4.3A voice-to-action latency coordinator.
 *
 * Uses [CarfuLatencyLog.nowMs] (monotonic) and [CarfuVoiceTrace] (`CARFU_VOICE`).
 * Does **not** introduce a competing logging architecture.
 *
 * Typical log line:
 * ```
 * CARFU_VOICE sid=… V2A stage=FINAL_TRANSCRIPT t_ms=… dt_prev=… dt_trigger=… dt_eos=… dt_complete_partial=…
 * ```
 *
 * Device proof of the 8–10s delay is the gap:
 * - `COMPLETE_PARTIAL_HELD` → `FINAL_TRANSCRIPT`  (candidate C / A)
 * - `LAST_SPEECH` → `FINAL_TRANSCRIPT`            (candidate A)
 * - `FINAL_TRANSCRIPT` → `INPUT_EVENT_RECEIVED`   (candidate G)
 * - `COMMAND_LOCKED` → `ACTION_REQUEST` → `INTENT_DISPATCH` (D / F)
 * - `ACTION_REQUEST` vs `TTS_REQUEST` / `TTS_START` (E — action is first)
 */
enum class VoiceToActionStage {
    TRIGGER,
    PRODUCT_LISTENING,
    LISTENING_READY,
    SR_INTENT_CONFIG,
    FIRST_SPEECH,
    PARTIAL_TRANSCRIPT,
    COMPLETE_PARTIAL_HELD,
    STABLE_COMPLETE_COMMIT,
    LAST_SPEECH,
    STOP_REQUEST,
    FINAL_TRANSCRIPT,
    INPUT_EVENT_RECEIVED,
    INPUT_EVENT_DISPATCHED,
    CANONICAL_COMMAND_READY,
    COMMAND_LOCKED,
    ACTION_REQUEST,
    INTENT_DISPATCH,
    TTS_REQUEST,
    TTS_START,
    SILENCE_WATCH_CANCELLED,
    SILENCE_TIMEOUT,
    SESSION_TERMINAL,
}

object VoiceToActionLatency {
    private val lock = Any()
    private var sessionId: String = ""
    private val t = LinkedHashMap<VoiceToActionStage, Long>()
    private var commandLocked: Boolean = false

    fun hasLockedCommand(): Boolean = synchronized(lock) { commandLocked }

    fun begin(newSessionId: String, extra: String = "") {
        val now = CarfuLatencyLog.nowMs()
        synchronized(lock) {
            sessionId = newSessionId
            t.clear()
            commandLocked = false
            t[VoiceToActionStage.TRIGGER] = now
        }
        emit(VoiceToActionStage.TRIGGER, now, extra.ifBlank { "voice_session_begin" })
    }

    fun mark(stage: VoiceToActionStage, extra: String = "") {
        val now = CarfuLatencyLog.nowMs()
        val shouldLog: Boolean
        synchronized(lock) {
            if (sessionId.isEmpty()) {
                return
            }
            if (t.containsKey(stage)) {
                shouldLog = false
            } else {
                t[stage] = now
                if (stage == VoiceToActionStage.COMMAND_LOCKED) {
                    commandLocked = true
                }
                shouldLog = true
            }
        }
        if (shouldLog) {
            emit(stage, now, extra)
        }
    }

    fun markRepeatable(stage: VoiceToActionStage, extra: String = "") {
        val now = CarfuLatencyLog.nowMs()
        synchronized(lock) {
            if (sessionId.isEmpty()) {
                return
            }
            t[stage] = now
        }
        emit(stage, now, extra)
    }

    fun millisOf(stage: VoiceToActionStage): Long? = synchronized(lock) { t[stage] }

    fun resetForTests() {
        synchronized(lock) {
            sessionId = ""
            t.clear()
            commandLocked = false
        }
    }

    fun end(reason: String) {
        mark(VoiceToActionStage.SESSION_TERMINAL, "reason=$reason summary=${summary()}")
        synchronized(lock) {
            sessionId = ""
            commandLocked = false
        }
    }

    fun summary(): String = synchronized(lock) { summaryLocked() }

    private fun summaryLocked(): String {
        if (t.isEmpty()) return "empty"
        val trigger = t[VoiceToActionStage.TRIGGER] ?: t.values.first()
        val parts = t.entries.joinToString(" ") { (stage, ts) ->
            "${stage.name}=${ts - trigger}"
        }
        val firstPartial = t[VoiceToActionStage.PARTIAL_TRANSCRIPT]
        val firstSpeech = t[VoiceToActionStage.FIRST_SPEECH]
        val complete = t[VoiceToActionStage.COMPLETE_PARTIAL_HELD]
        val commit = t[VoiceToActionStage.STABLE_COMPLETE_COMMIT]
        val eos = t[VoiceToActionStage.LAST_SPEECH]
        val stop = t[VoiceToActionStage.STOP_REQUEST]
        val finalT = t[VoiceToActionStage.FINAL_TRANSCRIPT]
        val received = t[VoiceToActionStage.INPUT_EVENT_RECEIVED]
        val locked = t[VoiceToActionStage.COMMAND_LOCKED]
        val action = t[VoiceToActionStage.ACTION_REQUEST]
        val intent = t[VoiceToActionStage.INTENT_DISPATCH]
        val ttsReq = t[VoiceToActionStage.TTS_REQUEST]
        val ttsStart = t[VoiceToActionStage.TTS_START]
        val critical = buildString {
            append("trigger_to_first_partial=${delta(trigger, firstPartial)} ")
            append("first_speech_to_complete_partial=${delta(firstSpeech, complete)} ")
            append("complete_partial_to_commit=${delta(complete, commit)} ")
            append("eos_to_stop_request=${delta(eos, stop)} ")
            append("stop_request_to_final=${delta(stop, finalT)} ")
            append("final_to_action=${delta(finalT, action)} ")
            append("complete_partial_to_action=${delta(complete, action)} ")
            append("eos_to_final=${delta(eos, finalT)} ")
            append("complete_partial_to_final=${delta(complete, finalT)} ")
            append("final_to_input=${delta(finalT, received)} ")
            append("lock_to_action=${delta(locked, action)} ")
            append("action_to_intent=${delta(action, intent)} ")
            append("action_to_tts_req=${delta(action, ttsReq)} ")
            append("tts_req_to_start=${delta(ttsReq, ttsStart)}")
        }
        return "$parts | $critical"
    }

    private fun delta(a: Long?, b: Long?): String {
        if (a == null || b == null) return "NA"
        return (b - a).toString()
    }

    private fun previousTimestampLocked(stage: VoiceToActionStage): Long {
        val trigger = t[VoiceToActionStage.TRIGGER] ?: 0L
        val entries = t.entries.toList()
        val idx = entries.indexOfFirst { it.key == stage }
        if (idx <= 0) return trigger
        return entries[idx - 1].value
    }

    private fun emit(stage: VoiceToActionStage, now: Long, extra: String) {
        val line = synchronized(lock) {
            val trigger = t[VoiceToActionStage.TRIGGER] ?: now
            val prevTs = previousTimestampLocked(stage)
            val dtPrev = now - prevTs
            val dtTrigger = now - trigger
            val dtEos = t[VoiceToActionStage.LAST_SPEECH]?.let { now - it }
            val dtComplete = t[VoiceToActionStage.COMPLETE_PARTIAL_HELD]?.let { now - it }
            val dtFinal = t[VoiceToActionStage.FINAL_TRANSCRIPT]?.let { now - it }
            buildString {
                append("V2A stage=").append(stage.name)
                append(" t_ms=").append(now)
                append(" dt_prev=").append(dtPrev)
                append(" dt_trigger=").append(dtTrigger)
                if (dtEos != null) append(" dt_eos=").append(dtEos)
                if (dtComplete != null) append(" dt_complete_partial=").append(dtComplete)
                if (dtFinal != null) append(" dt_final=").append(dtFinal)
                if (extra.isNotBlank()) append(" ").append(extra)
            }
        }
        CarfuVoiceTrace.event(line)
    }
}
