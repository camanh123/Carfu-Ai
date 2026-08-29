package org.stypox.dicio.io.session

/**
 * Command-side silence endpointing. Vosk reports empty utterance-ends on quiet
 * cabin after TTS; those must not stop listening before the user can speak.
 */
enum class EmptyEndpointAction {
    IGNORE,
    COUNT,
    STOP,
}

class CommandEndpointPolicy(
    private val silencesAllowed: Int,
    private val graceMs: Long = CommandSession.COMMAND_ENDPOINT_GRACE_MS,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private var startedAtMs: Long = 0L
    var remaining: Int = silencesAllowed.coerceAtLeast(1)
        private set

    fun onListeningStarted() {
        startedAtMs = clockMs()
        remaining = silencesAllowed.coerceAtLeast(1)
    }

    fun onNonEmptyPartial() {
        remaining = silencesAllowed.coerceAtLeast(1)
    }

    fun onEmptyOrWeakResult(): EmptyEndpointAction {
        if (clockMs() - startedAtMs < graceMs) {
            return EmptyEndpointAction.IGNORE
        }
        remaining -= 1
        return if (remaining <= 0) EmptyEndpointAction.STOP else EmptyEndpointAction.COUNT
    }
}
