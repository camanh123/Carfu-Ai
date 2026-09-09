package org.stypox.dicio.io.session

/**
 * Lightweight domain-aware endpoint delay selection. No LLM.
 */
object SemanticEndpointPolicy {
    enum class SemanticState {
        COMPLETE,
        INCOMPLETE,
        PREFIX_AMBIGUOUS,
        NAV_DESTINATION_COMPLETE,
        UNKNOWN,
    }

    enum class Decision {
        WAIT,
        FAST,
        NORMAL,
        EXTENDED,
        REJECT_INCOMPLETE,
    }

    const val FAST_MS = 550L
    const val NORMAL_MS = 1_000L
    const val EXTENDED_MS = 1_350L

    data class Snapshot(
        val state: SemanticState,
        val decision: Decision,
        val delayMs: Long,
    )

    fun evaluate(
        transcript: String,
        userSpeechStarted: Boolean,
        endOfSpeechSeen: Boolean,
    ): Snapshot {
        if (!userSpeechStarted) {
            return Snapshot(SemanticState.UNKNOWN, Decision.WAIT, NORMAL_MS)
        }
        val folded = VietnameseTranscript.foldForMatch(transcript)
        if (folded.isEmpty()) {
            return Snapshot(SemanticState.INCOMPLETE, Decision.WAIT, EXTENDED_MS)
        }
        val domain = CommandTranscriptNormalizer.detectDomain(folded)
        val state = semanticState(folded, domain)
        val decision = when (state) {
            SemanticState.INCOMPLETE ->
                if (endOfSpeechSeen) Decision.REJECT_INCOMPLETE else Decision.WAIT
            SemanticState.PREFIX_AMBIGUOUS -> Decision.NORMAL
            SemanticState.COMPLETE, SemanticState.NAV_DESTINATION_COMPLETE -> Decision.FAST
            SemanticState.UNKNOWN ->
                if (endOfSpeechSeen) Decision.NORMAL else Decision.WAIT
        }
        return Snapshot(state, decision, delayMs(decision))
    }

    fun delayMs(decision: Decision): Long = when (decision) {
        Decision.FAST -> FAST_MS
        Decision.NORMAL -> NORMAL_MS
        Decision.EXTENDED, Decision.REJECT_INCOMPLETE -> EXTENDED_MS
        Decision.WAIT -> NORMAL_MS
    }

    fun semanticState(
        folded: String,
        domain: CommandTranscriptNormalizer.Domain = CommandTranscriptNormalizer.detectDomain(folded),
    ): SemanticState {
        if (folded.isEmpty()) return SemanticState.INCOMPLETE
        return when (domain) {
            CommandTranscriptNormalizer.Domain.NAVIGATE -> {
                val dest = CommandTranscriptNormalizer.navigationDestination(folded)
                when {
                    dest.isNullOrBlank() || dest.length < 2 -> SemanticState.INCOMPLETE
                    else -> SemanticState.NAV_DESTINATION_COMPLETE
                }
            }
            CommandTranscriptNormalizer.Domain.OPEN_APP -> when {
                folded == "mo" || folded.endsWith(" mo") -> SemanticState.INCOMPLETE
                CommandTranscriptNormalizer.isPrefixAmbiguous(folded) ->
                    SemanticState.PREFIX_AMBIGUOUS
                CommandTranscriptNormalizer.matchAppInOpenDomain(folded) != null ->
                    SemanticState.COMPLETE
                else -> SemanticState.INCOMPLETE
            }
            CommandTranscriptNormalizer.Domain.CALL -> {
                val remainder = folded.removePrefix("goi cho").removePrefix("goi").trim()
                if (remainder.length < 2) SemanticState.INCOMPLETE else SemanticState.COMPLETE
            }
            CommandTranscriptNormalizer.Domain.TIME,
            CommandTranscriptNormalizer.Domain.VOLUME_UP,
            CommandTranscriptNormalizer.Domain.VOLUME_DOWN,
            -> {
                if (CommandTranscriptNormalizer.isSafeForFastPartial(folded) ||
                    CommandTranscriptNormalizer.isHighConfidenceMatch(folded)
                ) {
                    SemanticState.COMPLETE
                } else {
                    SemanticState.INCOMPLETE
                }
            }
            CommandTranscriptNormalizer.Domain.MEDIA -> {
                val parsed = VietnameseMediaCommandGrammar.parseFolded(folded)
                when {
                    parsed == null -> SemanticState.COMPLETE
                    parsed.complete -> SemanticState.COMPLETE
                    else -> SemanticState.INCOMPLETE
                }
            }
            CommandTranscriptNormalizer.Domain.UNKNOWN -> SemanticState.UNKNOWN
        }
    }
}
