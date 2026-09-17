package org.stypox.dicio.aliasdiagnostic

/**
 * One SpeechRecognizer session at a time. RAW STT / NORMALIZED always
 * belong to the current session. Previous finals are listed newest-first
 * in [history] and are never concatenated into the next RAW.
 */
data class DiagnosticSessionState(
    val sessionId: Int = 0,
    val listening: Boolean = false,
    val partial: String = "",
    val rawStt: String = "",
    val currentSnapshot: DiagnosticSnapshot? = null,
    val history: List<DiagnosticHistoryEntry> = emptyList(),
)

object DiagnosticSession {
    const val HISTORY_LIMIT = 20

    fun begin(previous: DiagnosticSessionState): DiagnosticSessionState =
        DiagnosticSessionState(
            sessionId = previous.sessionId + 1,
            listening = true,
            partial = "",
            rawStt = "",
            currentSnapshot = null,
            history = previous.history,
        )

    fun onPartial(
        state: DiagnosticSessionState,
        sessionId: Int,
        transcript: String,
        snapshot: DiagnosticSnapshot? = null,
    ): DiagnosticSessionState {
        if (sessionId != state.sessionId || !state.listening) return state
        return state.copy(
            partial = transcript,
            rawStt = transcript,
            currentSnapshot = snapshot,
        )
    }

    fun onFinal(
        state: DiagnosticSessionState,
        sessionId: Int,
        transcript: String,
        snapshot: DiagnosticSnapshot? = null,
    ): DiagnosticSessionState {
        if (sessionId != state.sessionId || !state.listening) return state
        val entry = snapshot?.toHistoryEntry(sessionId)
        val history = if (entry == null || transcript.isBlank()) {
            state.history
        } else {
            (listOf(entry) + state.history).take(HISTORY_LIMIT)
        }
        return state.copy(
            listening = false,
            rawStt = transcript,
            currentSnapshot = snapshot,
            history = history,
        )
    }

    fun onStop(state: DiagnosticSessionState, sessionId: Int): DiagnosticSessionState {
        if (sessionId != state.sessionId) return state
        return state.copy(listening = false)
    }
}
