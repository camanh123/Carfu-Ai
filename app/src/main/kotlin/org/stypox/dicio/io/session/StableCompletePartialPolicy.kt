package org.stypox.dicio.io.session

/**
 * Phase 4.3B.1 — conservative stable COMPLETE partial commit.
 *
 * Why this exists:
 * [AndroidSpeechInputDevice] never calls [android.speech.SpeechRecognizer.stopListening].
 * [RecognitionListener.onEndOfSpeech] is log-only for the product session (non-terminal).
 * After EOS the recognizer instance stays alive until [onResults]/[onError], then we
 * [SpeechRecognizer.cancel] + [SpeechRecognizer.destroy]. That passive OEM/network
 * finalization is the 8–10s gap Phase 4.3B tried to close.
 *
 * Phase 4.3B originally committed when either:
 * A. two consecutive identical COMPLETE fingerprints, OR
 * B. the first eligible COMPLETE fingerprint was unchanged for 350ms.
 *
 * Condition B is unsafe: Navigate becomes COMPLETE as soon as the destination has
 * two characters, so `"chỉ đường đến Mỹ"` could commit Navigate("Mỹ") while the user
 * was still about to say `"Đình"`. The 350ms Handler was also the only 4.3B path
 * that could cancel/destroy SpeechRecognizer before Android Final.
 *
 * Phase 4.3B.1 rule (AND, not OR):
 * 1. current session id
 * 2. current recognizer generation
 * 3. non-empty transcript
 * 4. eligible COMPLETE canonical command
 * 5. same canonical fingerprint in [CONSECUTIVE_IDENTICAL_TO_COMMIT] consecutive partials
 * 6. [RecognitionListener.onEndOfSpeech] confirmed for that session/generation
 *
 * No hold timer. No fingerprint/job/lock from empty, UNKNOWN, INCOMPLETE, or
 * pre-speech callbacks. CALL / Volume / Time stay excluded. Navigate fast-path
 * additionally requires a multi-token destination so a pause after `"Mỹ"` cannot
 * lock a still-growing place name.
 *
 * Calling [android.speech.SpeechRecognizer.stopListening] after EOS is still not
 * used. Only a successful current-session commit may [SessionCommandDecision.lockFinal]
 * and then take the existing cancel/destroy retire path into the existing executor.
 */
object StableCompletePartialPolicy {
    const val CONSECUTIVE_IDENTICAL_TO_COMMIT: Int = 2

    /** Hold-timer commit is retired. Kept as 0 so tests can prove it cannot fire. */
    const val STABILITY_MS: Long = 0L

    fun callsStopListeningAfterEndOfSpeech(): Boolean = false

    fun usesSpeechRecognizerStopListeningApi(): Boolean = false

    fun retireRecognizerUsesCancelThenDestroy(): Boolean = true

    fun firstCompletePartialExecutesImmediately(): Boolean = false

    fun holdTimerMayCommit(): Boolean = false

    fun requiresEndOfSpeech(): Boolean = true

    fun requiresConsecutiveIdenticalFingerprints(): Boolean = true

    fun isEnabled(): Boolean = true

    fun shouldObservePartial(text: String): Boolean = text.isNotBlank()

    fun navigateDestinationIsFastPathSafe(destination: String): Boolean {
        return tokenCount(destination) >= 2
    }

    fun tokenCount(text: String): Int =
        text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

    fun isEligible(result: UnderstandingResult): Boolean {
        if (result.rawTranscript.isBlank() && result.normalizedTranscript.isBlank()) {
            return false
        }
        if (result.completeness != SemanticCompleteness.COMPLETE) return false
        val command = result.command ?: return false
        return when (command) {
            is CanonicalCommand.Navigate ->
                command.destination.trim().length >= 2 &&
                    navigateDestinationIsFastPathSafe(command.destination)
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
        val sessionId: Long = 0L,
        val generation: Long = 0L,
        val eosConfirmed: Boolean = false,
    )

    private val lock = Any()
    private var sessionId: Long = 0L
    private var fingerprint: String = ""
    private var firstSeenMs: Long = 0L
    private var consecutive: Int = 0
    private var lastResult: UnderstandingResult? = null
    private var lastGeneration: Long = 0L
    private var committed: Boolean = false
    private var cancelled: Boolean = false
    private var eosConfirmed: Boolean = false

    fun bind(newSessionId: Long) {
        synchronized(lock) {
            sessionId = newSessionId
            fingerprint = ""
            firstSeenMs = 0L
            consecutive = 0
            lastResult = null
            lastGeneration = 0L
            committed = false
            cancelled = false
            eosConfirmed = false
        }
    }

    fun markCancelled() {
        synchronized(lock) {
            cancelled = true
            committed = false
            fingerprint = ""
            consecutive = 0
            lastResult = null
            lastGeneration = 0L
            firstSeenMs = 0L
            eosConfirmed = false
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

    fun hasPendingStabilityWork(): Boolean = synchronized(lock) {
        !cancelled && !committed && lastResult != null && fingerprint.isNotEmpty()
    }

    fun boundSessionIdForTests(): Long = synchronized(lock) { sessionId }

    fun lastGenerationForTests(): Long = synchronized(lock) { lastGeneration }

    fun eosConfirmedForTests(): Boolean = synchronized(lock) { eosConfirmed }

    fun onPartial(
        forSessionId: Long,
        result: UnderstandingResult,
        nowMs: Long,
        generation: Long = 0L,
    ): Observe = synchronized(lock) {
        if (cancelled || committed || forSessionId == 0L || forSessionId != sessionId) {
            return@synchronized Observe(
                decision = Decision.IGNORE,
                reason = "stale_or_cancelled",
                sessionId = sessionId,
                generation = lastGeneration,
                eosConfirmed = eosConfirmed,
            )
        }
        if (generation != 0L && lastGeneration != 0L && generation != lastGeneration) {
            return@synchronized Observe(
                decision = Decision.IGNORE,
                reason = "stale_generation",
                sessionId = sessionId,
                generation = lastGeneration,
                eosConfirmed = eosConfirmed,
            )
        }
        if (!StableCompletePartialPolicy.shouldObservePartial(result.rawTranscript) &&
            result.normalizedTranscript.isBlank()
        ) {
            return@synchronized Observe(
                decision = Decision.IGNORE,
                reason = "empty_transcript",
                sessionId = sessionId,
                generation = lastGeneration,
                eosConfirmed = eosConfirmed,
            )
        }
        if (!StableCompletePartialPolicy.isEligible(result)) {
            fingerprint = ""
            firstSeenMs = 0L
            consecutive = 0
            lastResult = null
            return@synchronized Observe(
                decision = Decision.IGNORE,
                reason = "ineligible",
                sessionId = sessionId,
                generation = lastGeneration,
                eosConfirmed = eosConfirmed,
            )
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
        if (generation != 0L) {
            lastGeneration = generation
        }
        decideLocked(result, generation = lastGeneration)
    }

    fun onEndOfSpeech(
        forSessionId: Long,
        @Suppress("UNUSED_PARAMETER") nowMs: Long,
        generation: Long = 0L,
    ): Observe = synchronized(lock) {
        if (cancelled || committed || forSessionId == 0L || forSessionId != sessionId) {
            return@synchronized Observe(
                decision = Decision.IGNORE,
                reason = "stale_or_cancelled",
                sessionId = sessionId,
                generation = lastGeneration,
                eosConfirmed = eosConfirmed,
            )
        }
        if (generation != 0L && lastGeneration != 0L && generation != lastGeneration) {
            return@synchronized Observe(
                decision = Decision.IGNORE,
                reason = "stale_generation",
                sessionId = sessionId,
                generation = lastGeneration,
                eosConfirmed = eosConfirmed,
            )
        }
        eosConfirmed = true
        val result = lastResult
            ?: return@synchronized Observe(
                decision = Decision.IGNORE,
                reason = "eos_without_eligible_partial",
                sessionId = sessionId,
                generation = lastGeneration,
                eosConfirmed = true,
            )
        if (!StableCompletePartialPolicy.isEligible(result)) {
            return@synchronized Observe(
                decision = Decision.IGNORE,
                reason = "ineligible",
                sessionId = sessionId,
                generation = lastGeneration,
                eosConfirmed = true,
            )
        }
        decideLocked(result, generation = lastGeneration)
    }

    /**
     * Retired 350ms hold path. A leftover Handler tick must never commit.
     * Always IGNORE so pre-speech / in-flight speech cannot be terminated by a timer.
     */
    fun onTimer(
        forSessionId: Long,
        @Suppress("UNUSED_PARAMETER") nowMs: Long,
    ): Observe = synchronized(lock) {
        Observe(
            decision = Decision.IGNORE,
            remainingMs = 0L,
            result = lastResult,
            reason = "hold_timer_disabled",
            consecutive = consecutive,
            fingerprint = fingerprint,
            sessionId = forSessionId,
            generation = lastGeneration,
            eosConfirmed = eosConfirmed,
        )
    }

    private fun decideLocked(result: UnderstandingResult, generation: Long): Observe {
        val ready = consecutive >= StableCompletePartialPolicy.CONSECUTIVE_IDENTICAL_TO_COMMIT &&
            eosConfirmed
        if (ready) {
            committed = true
            return Observe(
                decision = Decision.COMMIT,
                result = result,
                reason = "consecutive=$consecutive eos=true",
                consecutive = consecutive,
                fingerprint = fingerprint,
                sessionId = sessionId,
                generation = generation,
                eosConfirmed = true,
            )
        }
        return Observe(
            decision = Decision.WAIT,
            remainingMs = 0L,
            result = result,
            reason = "waiting consecutive=$consecutive/" +
                "${StableCompletePartialPolicy.CONSECUTIVE_IDENTICAL_TO_COMMIT} " +
                "eos=$eosConfirmed",
            consecutive = consecutive,
            fingerprint = fingerprint,
            sessionId = sessionId,
            generation = generation,
            eosConfirmed = eosConfirmed,
        )
    }
}
