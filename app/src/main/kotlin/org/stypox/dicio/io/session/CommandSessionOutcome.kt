package org.stypox.dicio.io.session

import java.util.concurrent.atomic.AtomicReference

/**
 * Exactly one terminal outcome per command session. Late SR timeout/no-speech callbacks
 * must not overwrite a completed EXECUTED or UNSUPPORTED result.
 */
object CommandSessionOutcome {
    enum class Kind {
        OPEN,
        NO_SPEECH,
        UNSUPPORTED,
        EXECUTED,
        SR_ERROR,
    }

    private val current = AtomicReference(Kind.OPEN)

    fun reset() {
        current.set(Kind.OPEN)
    }

    fun peek(): Kind = current.get()

    fun claim(kind: Kind): Boolean {
        while (true) {
            val existing = current.get()
            if (existing != Kind.OPEN) {
                CarfuLatencyLog.logSessionEvent(
                    "TERMINAL_IGNORED",
                    "existing=$existing requested=$kind",
                )
                return false
            }
            if (current.compareAndSet(Kind.OPEN, kind)) {
                CarfuLatencyLog.logSessionEvent("TERMINAL", "outcome=$kind")
                return true
            }
        }
    }

    fun resetForTests() {
        current.set(Kind.OPEN)
    }
}
