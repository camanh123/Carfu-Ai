package org.stypox.dicio.io.session

/**
 * Phase 4.3B — conservative stable COMPLETE partial commit.
 *
 * Why this exists:
 * [AndroidSpeechInputDevice] never calls [android.speech.SpeechRecognizer.stopListening].
 * [RecognitionListener.onEndOfSpeech] is log-only (product-non-terminal). After EOS the
 * recognizer instance stays alive until [onResults]/[onError], then we [SpeechRecognizer.cancel]
 * + [SpeechRecognizer.destroy]. That is passive OEM/network finalization — the 8–10s gap.
 *
 * Calling [android.speech.SpeechRecognizer.stopListening] *after* onEndOfSpeech is not
 * proven useful: EOS already means capture/endpointer stopped; remaining delay is remote
 * finalization. A second stop can be ignored or surface ERROR_CLIENT on some OEM stacks.
 * We therefore do **not** stop after EOS.
 *
 * Instead, a semantically COMPLETE Navigate / OpenApp / PlayMedia that stays stable may
 * lock once, cancel the recognizer (existing retire path), and execute. Later finals/errors
 * are ignored by generation + [CommandSessionOutcome] + [CanonicalActionGate].
 *
 * Stability (OR):
 * A. same canonical fingerprint in 2 consecutive eligible partials
 * B. same eligible COMPLETE fingerprint unchanged for [STABILITY_MS]
 *
 * First COMPLETE partial never commits immediately.
 * CALL / Volume / Time are excluded.
 */
object StableCompletePartialPolicy {
    const val STABILITY_MS: Long = 350L
    const val CONSECUTIVE_IDENTICAL_TO_COMMIT: Int = 2

    fun callsStopListeningAfterEndOfSpeech(): Boolean = false

    fun usesSpeechRecognizerStopListeningApi(): Boolean = false

    fun retireRecognizerUsesCancelThenDestroy(): Boolean = true

    fun firstCompletePartialExecutesImmediately(): Boolean = false

    fun isEnabled(): Boolean = true

    fun isEligible(result: UnderstandingResult): Boolean {
        if (result.completeness != SemanticCompleteness.COMPLETE) return false
        val command = result.command ?: return false
        return when (command) {
            is CanonicalCommand.Navigate -> command.destination.trim().length >= 2
            is CanonicalCommand.PlayMedia -> command.query.trim().length >= 2
            is CanonicalCommand.OpenApp -> {
                command.appName.trim().isNotEmpty() &&
                    !CommandTranscriptNormalizer.isPrefixAmbiguous(result.normalizedTranscript)
            }
            is CanonicalCommand.CallContact,
            is CanonicalCommand.Volume,
            CanonicalCommand.Time,
            -> false
        }
    }

    fun fingerprint(result: UnderstandingResult): String {
        return when (val command = result.command) {
            is CanonicalCommand.Navigate ->
                "NAVIGATE|${command.destination.trim()}"
            is CanonicalCommand.OpenApp ->
                "OPEN_APP|${command.appName.trim()}"
            is CanonicalCommand.PlayMedia ->
                "PLAY_MEDIA|${command.query.trim()}|${command.provider.orEmpty()}"
            else -> "INELIGIBLE"
        }
    }
}

object StableCompletePartialTracker {
    enum class Decision {
        IGNORE,
        WAIT,
        COMMIT,
    }

    data class Observe(
        val decision: Decision,
        val remainingMs: Long = 0L,
        val result: UnderstandingResult? = null,
        val reason: String = "",
        val consecutive: Int = 0,
        val fingerprint: String = "",
    )

    private val lock = Any()
    private var sessionId: Long = 0L
    private var fingerprint: String = ""
    private var firstSeenMs: Long = 0L
    private var consecutive: Int = 0
    private var lastResult: UnderstandingResult? = null
    private var committed: Boolean = false
    private var cancelled: Boolean = false

    fun bind(newSessionId: Long) {
        synchronized(lock) {
            sessionId = newSessionId
            fingerprint = ""
            firstSeenMs = 0L
            consecutive = 0
            lastResult = null
            committed = false
            cancelled = false
        }
    }

    fun markCancelled() {
        synchronized(lock) {
            cancelled = true
            committed = false
            fingerprint = ""
            consecutive = 0
            lastResult = null
        }
    }

    fun markCommitted() {
        synchronized(lock) {
            committed = true
        }
    }

    fun resetForTests() {
        bind(0L)
    }

    fun onPartial(
        forSessionId: Long,
        result: UnderstandingResult,
        nowMs: Long,
    ): Observe = synchronized(lock) {
        if (cancelled || committed || forSessionId == 0L || forSessionId != sessionId) {
            return@synchronized Observe(Decision.IGNORE, reason = "stale_or_cancelled")
        }
        if (!StableCompletePartialPolicy.isEligible(result)) {
            fingerprint = ""
            firstSeenMs = 0L
            consecutive = 0
            lastResult = null
            return@synchronized Observe(Decision.IGNORE, reason = "ineligible")
        }
        val fp = StableCompletePartialPolicy.fingerprint(result)
        if (fp == fingerprint) {
            consecutive += 1
        } else {
            fingerprint = fp
            firstSeenMs = nowMs
            consecutive = 1
        }
        lastResult = result
        decideLocked(result, nowMs)
    }

    fun onTimer(forSessionId: Long, nowMs: Long): Observe = synchronized(lock) {
        if (cancelled || committed || forSessionId != sessionId) {
            return@synchronized Observe(Decision.IGNORE, reason = "stale_or_cancelled")
        }
        val result = lastResult
            ?: return@synchronized Observe(Decision.IGNORE, reason = "no_observation")
        if (!StableCompletePartialPolicy.isEligible(result)) {
            return@synchronized Observe(Decision.IGNORE, reason = "ineligible")
        }
        decideLocked(result, nowMs)
    }

    private fun decideLocked(result: UnderstandingResult, nowMs: Long): Observe {
        if (consecutive >= StableCompletePartialPolicy.CONSECUTIVE_IDENTICAL_TO_COMMIT) {
            committed = true
            return Observe(
                decision = Decision.COMMIT,
                result = result,
                reason = "consecutive=$consecutive",
                consecutive = consecutive,
                fingerprint = fingerprint,
            )
        }
        val held = (nowMs - firstSeenMs).coerceAtLeast(0L)
        if (held >= StableCompletePartialPolicy.STABILITY_MS && consecutive >= 1) {
            committed = true
            return Observe(
                decision = Decision.COMMIT,
                result = result,
                reason = "held_ms=$held",
                consecutive = consecutive,
                fingerprint = fingerprint,
            )
        }
        return Observe(
            decision = Decision.WAIT,
            remainingMs = StableCompletePartialPolicy.STABILITY_MS - held,
            result = result,
            reason = "waiting consecutive=$consecutive held_ms=$held",
            consecutive = consecutive,
            fingerprint = fingerprint,
        )
    }
}
