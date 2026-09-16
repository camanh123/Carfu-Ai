package org.stypox.dicio.aliasdiagnostic

/**
 * One SpeechRecognizer session at a time. RAW STT / NORMALIZED always
 * belong to the current session. Previous finals may be listed in
 * [history] but are never concatenated into the next RAW.
 */
data class DiagnosticSessionState(
    val sessionId: Int = 0,
    val listening: Boolean = false,
    val partial: String = "",
    val rawStt: String = "",
    val history: List<String> = emptyList(),
)

object DiagnosticSession {
    const val HISTORY_LIMIT = 8

    fun begin(previous: DiagnosticSessionState): DiagnosticSessionState =
        DiagnosticSessionState(
            sessionId = previous.sessionId + 1,
            listening = true,
            partial = "",
            rawStt = "",
            history = previous.history,
        )

    fun onPartial(
        state: DiagnosticSessionState,
        sessionId: Int,
        transcript: String,
    ): DiagnosticSessionState {
        if (sessionId != state.sessionId || !state.listening) return state
        // Replace this session's live text. Never append a prior session.
        return state.copy(partial = transcript, rawStt = transcript)
    }

    fun onFinal(
        state: DiagnosticSessionState,
        sessionId: Int,
        transcript: String,
    ): DiagnosticSessionState {
        if (sessionId != state.sessionId || !state.listening) return state
        val history = if (transcript.isBlank()) {
            state.history
        } else {
            (state.history + transcript).takeLast(HISTORY_LIMIT)
        }
        return state.copy(
            listening = false,
            rawStt = transcript,
            history = history,
        )
    }

    fun onStop(state: DiagnosticSessionState, sessionId: Int): DiagnosticSessionState {
        if (sessionId != state.sessionId) return state
        return state.copy(listening = false)
    }
}
