package org.stypox.dicio.io.session

import android.os.Handler
import android.os.SystemClock
import org.stypox.dicio.io.input.CommandRecognitionPolicy

/**
 * App-side command endpointing for Android SpeechRecognizer.
 *
 * Requires real [userSpeechStarted] evidence. EndOfSpeech alone is never enough.
 * Endpoint delay is selected by [SemanticEndpointPolicy].
 */
class CommandSpeechEndpoint(
    private val handler: Handler,
    private val stabilityMs: Long = CommandRecognitionPolicy.ANDROID_PARTIAL_STABILITY_MS,
    private val clockMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val onEndpoint: (String) -> Unit,
) {
    private var lastPartialText = ""
    private var lastPartialChangeMs = 0L
    private var endOfSpeechMs = 0L
    private var userSpeechStarted = false
    private var selectedDelayMs = CommandRecognitionPolicy.ANDROID_SILENCE_ENDPOINT_MS

    private val endpointRunnable = Runnable { evaluateEndpoint() }

    fun reset() {
        handler.removeCallbacks(endpointRunnable)
        lastPartialText = ""
        lastPartialChangeMs = 0L
        endOfSpeechMs = 0L
        userSpeechStarted = false
        selectedDelayMs = CommandRecognitionPolicy.ANDROID_SILENCE_ENDPOINT_MS
    }

    fun onUserSpeechStarted() {
        userSpeechStarted = true
        endOfSpeechMs = 0L
        handler.removeCallbacks(endpointRunnable)
    }

    fun onPartial(text: String) {
        if (text.isBlank()) return
        if (!userSpeechStarted) return
        val now = clockMs()
        if (text != lastPartialText) {
            lastPartialText = text
            lastPartialChangeMs = now
        }
        refreshSemanticDelay(endOfSpeechSeen = endOfSpeechMs > 0L)
        scheduleEndpoint()
    }

    fun onEndOfSpeech() {
        if (!userSpeechStarted) {
            CarfuLatencyLog.logPipelineStage("PRE_SPEECH_EOS")
            return
        }
        endOfSpeechMs = clockMs()
        refreshSemanticDelay(endOfSpeechSeen = true)
        scheduleEndpoint()
    }

    fun cancel() {
        handler.removeCallbacks(endpointRunnable)
    }

    fun currentPartial(): String = lastPartialText

    fun hasUserSpeechStarted(): Boolean = userSpeechStarted

    /** Test-only: run pending endpoint evaluation without waiting on [Handler]. */
    internal fun evaluateEndpointNow() {
        evaluateEndpoint()
    }

    private fun refreshSemanticDelay(endOfSpeechSeen: Boolean) {
        val snap = SemanticEndpointPolicy.evaluate(
            transcript = lastPartialText,
            userSpeechStarted = userSpeechStarted,
            endOfSpeechSeen = endOfSpeechSeen,
        )
        selectedDelayMs = snap.delayMs
        CarfuLatencyLog.logPipelineStage(
            "SEMANTIC_STATE",
            "state=${snap.state} decision=${snap.decision} delay_ms=${snap.delayMs}",
        )
        CarfuLatencyLog.logPipelineStage(
            "ENDPOINT_DELAY_SELECTED",
            "delay_ms=${snap.delayMs} decision=${snap.decision}",
        )
    }

    private fun scheduleEndpoint() {
        handler.removeCallbacks(endpointRunnable)
        handler.postDelayed(endpointRunnable, selectedDelayMs)
    }

    private fun evaluateEndpoint() {
        if (!userSpeechStarted) return
        if (endOfSpeechMs <= 0L) return
        if (lastPartialText.isBlank()) return
        val snap = SemanticEndpointPolicy.evaluate(
            transcript = lastPartialText,
            userSpeechStarted = true,
            endOfSpeechSeen = true,
        )
        if (snap.decision == SemanticEndpointPolicy.Decision.WAIT) {
            return
        }
        if (snap.decision == SemanticEndpointPolicy.Decision.REJECT_INCOMPLETE) {
            // Incomplete after EOS: do not invent a command; wait for Google final/error.
            return
        }
        val now = clockMs()
        val stableFor = now - lastPartialChangeMs
        val silentFor = now - endOfSpeechMs
        if (stableFor < stabilityMs || silentFor < snap.delayMs) {
            val remaining = maxOf(snap.delayMs - silentFor, stabilityMs - stableFor, 50L)
            handler.postDelayed(endpointRunnable, remaining)
            return
        }
        CarfuLatencyLog.logSessionEvent(
            "ENDPOINT_FIRED",
            "len=${lastPartialText.length} delay_ms=${snap.delayMs} state=${snap.state}",
        )
        onEndpoint(lastPartialText)
    }
}
