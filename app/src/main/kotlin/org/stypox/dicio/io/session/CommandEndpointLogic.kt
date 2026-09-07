package org.stypox.dicio.io.session

/**
 * Pure endpoint fire decision for JVM tests.
 * Requires user-speech evidence + EndOfSpeech + stable transcript + semantic delay.
 */
object CommandEndpointLogic {
    fun shouldFire(
        lastPartialText: String,
        lastPartialChangeMs: Long,
        endOfSpeechMs: Long,
        nowMs: Long,
        silenceEndpointMs: Long,
        stabilityMs: Long,
        userSpeechStarted: Boolean,
    ): Boolean {
        if (!userSpeechStarted) return false
        if (lastPartialText.isBlank()) return false
        if (endOfSpeechMs <= 0L) return false
        val stableFor = nowMs - lastPartialChangeMs
        if (stableFor < stabilityMs) return false
        val silentFor = nowMs - endOfSpeechMs
        return silentFor >= silenceEndpointMs
    }
}
