package org.stypox.dicio.io.session

/**
 * Phase 2 typed command object.
 *
 * One [CanonicalCommand] must drive both confirmation TTS and (later) execution.
 * Do not re-parse the raw transcript independently for those consumers.
 */
sealed class CanonicalCommand {
    data class Navigate(val destination: String) : CanonicalCommand()

    data class OpenApp(val appName: String) : CanonicalCommand()

    data class PlayMedia(
        val query: String,
        val provider: String?,
    ) : CanonicalCommand()

    data class Volume(val up: Boolean) : CanonicalCommand()

    data object Time : CanonicalCommand()

    data class CallContact(val contactName: String) : CanonicalCommand()
}

enum class SemanticCompleteness {
    /** Intent + required entities present; eligible for execution when wired. */
    COMPLETE,
    /** Domain recognized but required entity missing / particle-only. */
    INCOMPLETE,
    /** No supported domain / intent. */
    UNKNOWN,
}

/**
 * Full understanding result for one candidate inside one VoiceSession.
 */
data class UnderstandingResult(
    val sessionId: Long,
    val rawTranscript: String,
    val normalizedTranscript: String,
    val intent: VoiceIntent,
    val entities: Map<String, String> = emptyMap(),
    val confidence: Float,
    val completeness: SemanticCompleteness,
    /** True only when [command] is complete and Phase-2/3 may execute it. */
    val executable: Boolean,
    val command: CanonicalCommand?,
    val candidateIndex: Int = 0,
    val recognizerConfidence: Float = 0f,
    val reason: String = "",
) {
    val destination: String?
        get() = entities[CommandEntityKeys.DESTINATION]?.takeIf { it.isNotBlank() }
    val query: String?
        get() = entities[CommandEntityKeys.QUERY]?.takeIf { it.isNotBlank() }
    val provider: String?
        get() = entities[CommandEntityKeys.PROVIDER]?.takeIf { it.isNotBlank() }
    val app: String?
        get() = entities[CommandEntityKeys.APP]?.takeIf { it.isNotBlank() }

    fun toCommandUnderstanding(): CommandUnderstanding = CommandUnderstanding(
        rawTranscript = rawTranscript,
        normalizedTranscript = normalizedTranscript,
        intent = intent,
        entities = entities,
        confidence = confidence,
        completeness = completeness,
        executable = executable,
        command = command,
    )

    companion object {
        fun incomplete(
            sessionId: Long,
            raw: String,
            normalized: String,
            intent: VoiceIntent,
            reason: String,
            candidateIndex: Int = 0,
            recognizerConfidence: Float = 0f,
            entities: Map<String, String> = emptyMap(),
            confidence: Float = 0.4f,
        ): UnderstandingResult = UnderstandingResult(
            sessionId = sessionId,
            rawTranscript = raw,
            normalizedTranscript = normalized,
            intent = intent,
            entities = entities,
            confidence = confidence,
            completeness = SemanticCompleteness.INCOMPLETE,
            executable = false,
            command = null,
            candidateIndex = candidateIndex,
            recognizerConfidence = recognizerConfidence,
            reason = reason,
        )

        fun unknown(
            sessionId: Long,
            raw: String,
            normalized: String,
            candidateIndex: Int = 0,
            recognizerConfidence: Float = 0f,
            reason: String = "unknown",
        ): UnderstandingResult = UnderstandingResult(
            sessionId = sessionId,
            rawTranscript = raw,
            normalizedTranscript = normalized,
            intent = VoiceIntent.UNKNOWN,
            confidence = 0f,
            completeness = SemanticCompleteness.UNKNOWN,
            executable = false,
            command = null,
            candidateIndex = candidateIndex,
            recognizerConfidence = recognizerConfidence,
            reason = reason,
        )
    }
}
