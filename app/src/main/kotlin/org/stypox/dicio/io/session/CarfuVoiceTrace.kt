package org.stypox.dicio.io.session

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Phase 4.2 real-device STT lifecycle trace.
 *
 * One line per event, chronological, tagged [TAG] so a single logcat filter
 * captures a MIC/MODE session. Never logs audio buffers.
 *
 * Format: `CARFU_VOICE session=<id> <EVENT> ...`
 */
object CarfuVoiceTrace {
    const val TAG = "CARFU_VOICE"

    @Volatile
    var sessionId: Long = 0L
        private set

    @Volatile
    var origin: String = "NONE"
        private set

    private val events = CopyOnWriteArrayList<String>()

    fun bind(sessionId: Long, origin: VoiceTriggerManager.Origin) {
        this.sessionId = sessionId
        this.origin = when (origin) {
            VoiceTriggerManager.Origin.HARDWARE_MODE -> "HARDWARE_MODE"
            VoiceTriggerManager.Origin.UI_MODE -> "UI_MODE"
            else -> origin.name
        }
    }

    fun trigger(source: String) {
        event("TRIGGER source=$source")
    }

    fun sessionStart() {
        event("SESSION_START origin=$origin")
    }

    fun srCreate(packageName: String = "", className: String = "") {
        val extra = if (packageName.isEmpty()) "" else " package=$packageName class=$className"
        event("SR_CREATE$extra")
    }

    fun srStartListening() {
        event("SR_START_LISTENING")
    }

    fun srReady() {
        event("SR_READY")
    }

    fun srBeginSpeech() {
        event("SR_BEGIN_SPEECH")
    }

    fun srEndSpeech() {
        event("SR_END_SPEECH")
    }

    fun srPartial(text: String) {
        val preview = previewText(text)
        event("SR_PARTIAL text=$preview")
    }

    fun srFinal(text: String, candidates: Int) {
        val preview = previewText(text)
        event("SR_FINAL text=$preview candidates=$candidates")
    }

    fun srError(code: Int, name: String, action: String, generation: Long, current: Long) {
        event(
            "SR_ERROR code=$code name=$name action=$action " +
                "gen=$generation current=$current stale=${generation != current}",
        )
    }

    fun srAbsorbed(reason: String) {
        event("SR_ABSORBED reason=$reason keep_product_session=true rearm=false")
    }

    fun stopRequest(source: String) {
        event("STOP_REQUEST source=$source")
    }

    fun terminal(reason: String) {
        event("TERMINAL reason=$reason")
    }

    fun sessionEnd(reason: String) {
        event("SESSION_END reason=$reason")
    }

    fun audioFocus(result: Int, granted: Boolean) {
        event("AUDIO_FOCUS result=$result granted=$granted")
    }

    fun audioFocusFailure(detail: String) {
        event("AUDIO_FOCUS_FAILURE detail=$detail")
    }

    fun permissionOrAvailability(reason: String) {
        event("SR_REFUSED reason=$reason")
    }

    fun coroutineCancelled(where: String) {
        event("COROUTINE_CANCELLED where=$where")
    }

    fun staleCallback(callback: String, generation: Long, current: Long) {
        event("STALE_CALLBACK callback=$callback gen=$generation current=$current")
    }

    fun event(payload: String) {
        val sid = sessionId
        val line = "session=$sid $payload"
        events.add(line)
        CarfuLog.i(TAG, line)
    }

    fun eventsForTests(): List<String> = events.toList()

    fun resetForTests() {
        sessionId = 0L
        origin = "NONE"
        events.clear()
    }

    private fun previewText(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return "<empty>"
        return if (trimmed.length <= 80) trimmed else trimmed.take(77) + "..."
    }
}
