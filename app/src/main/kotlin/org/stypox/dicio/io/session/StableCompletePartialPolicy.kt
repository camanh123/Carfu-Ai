package org.stypox.dicio.io.session

/**
 * Phase 4.4 — semantic COMPLETE partial commit for OPEN_APP / PLAY_MEDIA.
 *
 * Device-proven (Phase 4.3b.2 sessions 15–16): CARFU OEM often emits no useful
 * [android.speech.RecognitionListener.onEndOfSpeech] and no Android Final before
 * [CommandRecognitionPolicy.ANDROID_LISTEN_TIMEOUT_MS] (12s from startListening).
 * Phase 4.3B.1 therefore WAIT-ed on COMPLETE OpenApp("YouTube") until
 * SR_HARD_CEILING → transcript_rescued (~8–9s after COMPLETE_PARTIAL_HELD).
 *
 * Phase 4.4 commits from **semantic completeness**, not time:
 * - OPEN_APP: exact unique catalog app, not prefix-ambiguous, not a prefix of
 *   a higher-priority supported canonical command (Navigate `"mở bản đồ đến …"`).
 * - PLAY_MEDIA: complete query + known provider (compositional Vietnamese media
 *   grammar; not a hardcoded utterance list).
 *
 * NAVIGATE stays on the conservative 4.3B.1 rule: two consecutive identical
 * COMPLETE fingerprints **and** onEndOfSpeech, plus a multi-token destination.
 *
 * Not used:
 * - arbitrary stability / 350ms / 500ms hold timers ([holdTimerMayCommit] = false)
 * - requiring two identical OPEN_APP partials (device showed one COMPLETE)
 * - requiring EOS for OPEN_APP / PLAY_MEDIA on this OEM
 *
 * [android.speech.SpeechRecognizer.stopListening] is still not used. A successful
 * current-session commit [SessionCommandDecision.lockFinal]s then takes the
 * existing cancel/destroy retire path into the existing executor.
 * SR_HARD_CEILING remains the failsafe when no safely complete command exists.
 */
object StableCompletePartialPolicy {
    const val CONSECUTIVE_IDENTICAL_TO_COMMIT: Int = 2

    /** Hold-timer commit is retired. Kept as 0 so tests can prove it cannot fire. */
    const val STABILITY_MS: Long = 0L

    fun callsStopListeningAfterEndOfSpeech(): Boolean = false

    fun usesSpeechRecognizerStopListeningApi(): Boolean = false

    fun retireRecognizerUsesCancelThenDestroy(): Boolean = true

    /**
     * Arbitrary first COMPLETE of any intent still must not execute (Navigate).
     * OPEN_APP / PLAY_MEDIA use [isSemanticEarlyCommitSafe] instead.
     */
    fun firstCompletePartialExecutesImmediately(): Boolean = false

    fun holdTimerMayCommit(): Boolean = false

    /** Conservative Navigate / non-semantic path still requires EOS. */
    fun requiresEndOfSpeech(): Boolean = true

    fun requiresEndOfSpeechForOpenApp(): Boolean = false

    fun requiresEndOfSpeechForPlayMedia(): Boolean = false

    fun requiresConsecutiveIdenticalFingerprints(): Boolean = true

    fun semanticOpenAppMayCommitWithoutEos(): Boolean = true

    fun semanticPlayMediaMayCommitWithoutEos(): Boolean = true

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
                    !isOpenAppPrefixAmbiguous(result)
            }
            is CanonicalCommand.CallContact,
            is CanonicalCommand.Volume,
            CanonicalCommand.Time,
            -> false
        }
    }

    /**
     * Semantic early commit: complete unique OPEN_APP catalog entity, or
     * complete PLAY_MEDIA query+known provider. No EOS. No stability timer.
     */
    fun isSemanticEarlyCommitSafe(result: UnderstandingResult): Boolean {
        if (!isEligible(result)) return false
        return when (result.command) {
            is CanonicalCommand.OpenApp -> isOpenAppSemanticCommitSafe(result)
            is CanonicalCommand.PlayMedia -> isPlayMediaSemanticCommitSafe(result)
            else -> false
        }
    }

    fun isOpenAppSemanticCommitSafe(result: UnderstandingResult): Boolean {
        val command = result.command as? CanonicalCommand.OpenApp ?: return false
        if (result.intent != VoiceIntent.OPEN_APP) return false
        if (result.completeness != SemanticCompleteness.COMPLETE) return false
        if (command.appName.trim().isEmpty()) return false
        if (!VietnameseCommandUnderstanding.isExactCatalogOpenApp(result)) return false
        if (isOpenAppPrefixAmbiguous(result)) return false
        if (canExtendToHigherPriorityCanonical(result)) return false
        return true
    }

    fun isPlayMediaSemanticCommitSafe(result: UnderstandingResult): Boolean {
        val command = result.command as? CanonicalCommand.PlayMedia ?: return false
        if (result.intent != VoiceIntent.PLAY_MEDIA) return false
        if (result.completeness != SemanticCompleteness.COMPLETE) return false
        if (result.reason != "media_complete") return false
        if (command.query.trim().length < 2) return false
        if (!VietnameseCommandUnderstanding.isKnownPlayMediaProvider(command.provider)) {
            return false
        }
        return true
    }

    /**
     * OPEN_APP prefix-ambiguity:
     * 1. Known extendable catalog aliases (`mo smarttube` → `mo smarttube beta`).
     * 2. Prefix of a supported NAVIGATE command (`mo ban do` → `mo ban do den …`).
     *
     * `"Mở YouTube"` is **not** a prefix of a higher-priority supported command:
     * YouTube Music is not in the OpenApp catalog. PLAY_MEDIA needs a media query
     * plus an explicit provider preposition (`trên` / `bằng` / …), which `"Mở YouTube"`
     * does not contain.
     */
    fun isOpenAppPrefixAmbiguous(result: UnderstandingResult): Boolean {
        val folded = result.normalizedTranscript
        if (folded.isEmpty()) return true
        if (CommandTranscriptNormalizer.isPrefixAmbiguous(folded)) return true
        if (CommandTranscriptNormalizer.isPrefixOfSupportedNavigation(folded)) return true
        return false
    }

    fun canExtendToHigherPriorityCanonical(result: UnderstandingResult): Boolean {
        if (result.command !is CanonicalCommand.OpenApp) return false
        return CommandTranscriptNormalizer.isPrefixOfSupportedNavigation(
            result.normalizedTranscript,
        )
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
    /**
     * True once this session observed PLAY_MEDIA (complete or incomplete with a query).
     * Exact catalog OpenApp("YouTube") must not steal that in-progress media command
     * and launch YouTube home. Bare "Mở YouTube" with no prior media still early-commits.
     */
    private var sawPlayMediaIntent: Boolean = false

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
            sawPlayMediaIntent = false
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
            sawPlayMediaIntent = false
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

    fun committedForTests(): Boolean = synchronized(lock) { committed }

    fun sawPlayMediaIntentForTests(): Boolean = synchronized(lock) { sawPlayMediaIntent }

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
        notePlayMediaIntent(result)
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
        notePlayMediaIntent(result)
        if (StableCompletePartialPolicy.isSemanticEarlyCommitSafe(result)) {
            if (openAppYouTubeBlockedByPlayMedia(result)) {
                return Observe(
                    decision = Decision.WAIT,
                    result = result,
                    reason = "play_media_in_progress_blocks_open_app_youtube",
                    consecutive = consecutive,
                    fingerprint = fingerprint,
                    sessionId = sessionId,
                    generation = generation,
                    eosConfirmed = eosConfirmed,
                )
            }
            committed = true
            val why = when (result.command) {
                is CanonicalCommand.OpenApp -> "semantic_open_app"
                is CanonicalCommand.PlayMedia -> "semantic_play_media"
                else -> "semantic_complete"
            }
            return Observe(
                decision = Decision.COMMIT,
                result = result,
                reason = why,
                consecutive = consecutive,
                fingerprint = fingerprint,
                sessionId = sessionId,
                generation = generation,
                eosConfirmed = eosConfirmed,
            )
        }
        val ready = consecutive >= StableCompletePartialPolicy.CONSECUTIVE_IDENTICAL_TO_COMMIT &&
            eosConfirmed
        if (ready) {
            if (openAppYouTubeBlockedByPlayMedia(result)) {
                return Observe(
                    decision = Decision.WAIT,
                    result = result,
                    reason = "play_media_in_progress_blocks_open_app_youtube",
                    consecutive = consecutive,
                    fingerprint = fingerprint,
                    sessionId = sessionId,
                    generation = generation,
                    eosConfirmed = true,
                )
            }
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

    private fun notePlayMediaIntent(result: UnderstandingResult) {
        if (result.intent == VoiceIntent.PLAY_MEDIA) {
            sawPlayMediaIntent = true
        }
    }

    private fun openAppYouTubeBlockedByPlayMedia(result: UnderstandingResult): Boolean {
        if (!sawPlayMediaIntent) return false
        return VietnameseCommandUnderstanding.isExactCatalogOpenAppYouTube(result)
    }
}
