package org.stypox.dicio.io.session

import org.stypox.dicio.io.input.AsrTestProfile

/**
 * On-device A/B evidence for one SpeechRecognizer session.
 * Text recorded as RAW_FINAL is the recognizer string, before normalization.
 * Appended to [CarfuDiag.copyableLog] so the existing copy action is enough.
 */
object AsrAbEvidence {
    private const val MAX_SESSIONS = 8
    private const val MAX_PARTIALS = 40

    private val lock = Any()
    private val closed = ArrayDeque<Session>()
    private var open: Session? = null

    private class Session(
        val profile: String,
        val config: String,
    ) {
        val rawPartials = ArrayDeque<String>()
        var rawFinal: String? = null
        var duplicateRawFinalRejected: Int = 0
        var normalized: String? = null
        var nlu: String? = null
        var canonical: String? = null
        var execution: String? = null
        var asrError: String? = null
        var timings: String? = null
    }

    fun begin(profile: AsrTestProfile, config: String) {
        synchronized(lock) {
            open?.let { archive(it) }
            open = Session(profile = profile.name, config = config)
        }
    }

    fun noteRawPartial(text: String) {
        synchronized(lock) {
            val session = open ?: return
            if (session.rawPartials.size >= MAX_PARTIALS) {
                session.rawPartials.removeFirst()
            }
            session.rawPartials.addLast(text)
        }
    }

    /**
     * Records the recognizer's final string once.
     * A second call keeps the first string and returns false.
     */
    fun noteRawFinal(text: String): Boolean = synchronized(lock) {
        val session = open ?: Session(profile = AsrTestProfile.DEFAULT.name, config = "").also {
            open = it
        }
        if (session.rawFinal != null) {
            session.duplicateRawFinalRejected += 1
            return@synchronized false
        }
        session.rawFinal = text
        true
    }

    fun noteNormalized(text: String) {
        synchronized(lock) { (open ?: return).normalized = text }
    }

    fun noteNlu(text: String) {
        synchronized(lock) { (open ?: return).nlu = text }
    }

    fun noteCanonical(text: String) {
        synchronized(lock) { (open ?: return).canonical = text }
    }

    fun noteExecution(text: String) {
        synchronized(lock) { (open ?: return).execution = text }
    }

    fun noteAsrError(text: String) {
        synchronized(lock) { (open ?: return).asrError = text }
    }

    fun noteTimings(text: String) {
        synchronized(lock) { (open ?: return).timings = text }
    }

    fun rawFinalCountForTests(): Int = synchronized(lock) {
        val session = open ?: return 0
        (if (session.rawFinal != null) 1 else 0)
    }

    fun duplicateRawFinalRejectedForTests(): Int = synchronized(lock) {
        open?.duplicateRawFinalRejected ?: 0
    }

    fun rawFinalForTests(): String? = synchronized(lock) { open?.rawFinal }

    fun copyableSection(): String = synchronized(lock) {
        buildString {
            appendLine("ACTIVE_PROFILE: ${activeProfileName()}")
            if (closed.isEmpty() && open == null) {
                appendLine("(no recognition session yet)")
                return@buildString
            }
            closed.forEachIndexed { index, session ->
                appendSession(index + 1, session)
            }
            open?.let { appendSession(closed.size + 1, it) }
        }
    }

    fun resetForTests() {
        synchronized(lock) {
            closed.clear()
            open = null
        }
    }

    private fun activeProfileName(): String = try {
        org.stypox.dicio.io.input.AsrTestProfileState.profile.value.name
    } catch (_: Throwable) {
        AsrTestProfile.DEFAULT.name
    }

    private fun archive(session: Session) {
        closed.addLast(session)
        while (closed.size > MAX_SESSIONS) {
            closed.removeFirst()
        }
    }

    private fun StringBuilder.appendSession(index: Int, session: Session) {
        appendLine("--- session $index ---")
        appendLine("PROFILE: ${session.profile}")
        appendLine("CONFIG: ${session.config}")
        appendLine("RAW_PARTIALS:")
        if (session.rawPartials.isEmpty()) {
            appendLine("(none)")
        } else {
            session.rawPartials.forEach { appendLine(it) }
        }
        appendLine("RAW_FINAL: ${session.rawFinal ?: "(none)"}")
        appendLine("NORMALIZED: ${session.normalized ?: "(none)"}")
        appendLine("NLU: ${session.nlu ?: "(none)"}")
        appendLine("CANONICAL_COMMAND: ${session.canonical ?: "(none)"}")
        appendLine("EXECUTION: ${session.execution ?: "(none)"}")
        appendLine("ASR_ERROR: ${session.asrError ?: "(none)"}")
        appendLine("TIMINGS: ${session.timings ?: "(none)"}")
    }
}
