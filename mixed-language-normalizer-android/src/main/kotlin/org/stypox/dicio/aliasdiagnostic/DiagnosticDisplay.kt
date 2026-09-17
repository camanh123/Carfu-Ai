package org.stypox.dicio.aliasdiagnostic

import org.stypox.dicio.aliasnormalizer.DefaultProviderAliasNormalizer
import org.stypox.dicio.aliasnormalizer.ProviderResolution
import java.util.Locale

/**
 * Maps frozen Phase 2A [ProviderResolution] onto diagnostic UI fields.
 * Does not duplicate scoring, aliases, or thresholds.
 */
data class DiagnosticSnapshot(
    val status: String,
    val partialTranscript: String,
    val rawStt: String,
    val normalized: String,
    val mediaContext: String,
    val providerSlot: String,
    val candidate: String,
    val canonicalProvider: String,
    val exactMatch: String,
    val aliasMatch: String,
    val aliasMatched: String,
    val lexicalScore: String,
    val phoneticScore: String,
    val finalScore: String,
    val confidence: String,
    val ambiguous: String,
    val matchType: String,
    val shouldRewrite: String,
    val reason: String,
) {
    val provider: String get() = canonicalProvider
    val changed: Boolean get() = shouldRewrite == "YES"

    fun format(): String = buildString {
        appendLine("STATUS: $status")
        appendLine("PARTIAL: $partialTranscript")
        appendLine("RAW STT: $rawStt")
        appendLine("NORMALIZED: $normalized")
        appendLine("MEDIA CONTEXT: $mediaContext")
        appendLine("PROVIDER SLOT: $providerSlot")
        appendLine("CANDIDATE: $candidate")
        appendLine("CANONICAL PROVIDER: $canonicalProvider")
        appendLine("EXACT MATCH: $exactMatch")
        appendLine("ALIAS MATCH: $aliasMatch")
        appendLine("LEXICAL SCORE: $lexicalScore")
        appendLine("PHONETIC SCORE: $phoneticScore")
        appendLine("FINAL SCORE: $finalScore")
        appendLine("CONFIDENCE: $confidence")
        appendLine("AMBIGUOUS: $ambiguous")
        appendLine("MATCH TYPE: $matchType")
        appendLine("SHOULD REWRITE: $shouldRewrite")
        appendLine("REASON: $reason")
        appendLine("LOCALE: ${AliasDiagnosticPolicy.SPEECH_LOCALE}")
        appendLine("PRODUCTION_WIRED: ${AliasDiagnosticPolicy.PRODUCTION_WIRED}")
    }

    fun toHistoryEntry(sessionNumber: Int): DiagnosticHistoryEntry = DiagnosticHistoryEntry(
        sessionNumber = sessionNumber,
        raw = rawStt,
        normalized = normalized,
        candidate = candidate,
        canonicalProvider = canonicalProvider,
        lexicalScore = lexicalScore,
        phoneticScore = phoneticScore,
        confidence = confidence,
        matchType = matchType,
        rewrite = shouldRewrite,
    )
}

data class DiagnosticHistoryEntry(
    val sessionNumber: Int,
    val raw: String,
    val normalized: String,
    val candidate: String,
    val canonicalProvider: String,
    val lexicalScore: String,
    val phoneticScore: String,
    val confidence: String,
    val matchType: String,
    val rewrite: String,
) {
    fun compact(): String =
        "#$sessionNumber RAW=$raw | NORM=$normalized | CAND=$candidate | " +
            "CANON=$canonicalProvider | LEX=$lexicalScore | PHON=$phoneticScore | " +
            "CONF=$confidence | TYPE=$matchType | REWRITE=$rewrite"
}

object DiagnosticDisplay {
    const val UNKNOWN = "UNKNOWN"

    fun defaultNormalizer(): DefaultProviderAliasNormalizer = DefaultProviderAliasNormalizer()

    fun of(
        normalizer: DefaultProviderAliasNormalizer,
        rawTranscript: String,
        partialTranscript: String = "",
        status: String,
    ): DiagnosticSnapshot {
        val resolution = if (rawTranscript.isEmpty()) {
            null
        } else {
            normalizer.resolve(rawTranscript)
        }
        return from(
            rawTranscript = rawTranscript,
            resolution = resolution,
            partialTranscript = partialTranscript,
            status = status,
        )
    }

    fun from(
        rawTranscript: String,
        resolution: ProviderResolution?,
        partialTranscript: String = "",
        status: String,
    ): DiagnosticSnapshot {
        val rewritten = resolution?.rewrittenTranscript
        return DiagnosticSnapshot(
            status = status,
            partialTranscript = partialTranscript,
            rawStt = rawTranscript,
            normalized = rewritten ?: rawTranscript,
            mediaContext = yn(resolution?.mediaCommand == true),
            providerSlot = yn(resolution?.providerSlotDetected == true),
            candidate = resolution?.originalCandidate.orEmpty(),
            canonicalProvider = resolution?.canonicalProvider ?: if (resolution == null) "" else UNKNOWN,
            exactMatch = yn(resolution?.exactMatch == true),
            aliasMatch = yn(resolution?.exactAliasMatch == true),
            aliasMatched = resolution?.matchedAlias.orEmpty(),
            lexicalScore = score(resolution?.lexicalScore ?: 0.0),
            phoneticScore = score(resolution?.phoneticScore ?: 0.0),
            finalScore = score(resolution?.finalScore ?: 0.0),
            confidence = resolution?.confidence?.name ?: "",
            ambiguous = yn(resolution?.ambiguous == true),
            matchType = resolution?.matchType?.name ?: "",
            shouldRewrite = yn(resolution?.shouldRewrite == true),
            reason = resolution?.reason.orEmpty(),
        )
    }

    fun formatDump(current: DiagnosticSnapshot, history: List<DiagnosticHistoryEntry>): String =
        buildString {
            append(current.format())
            appendLine("HISTORY (newest first, cap=${DiagnosticSession.HISTORY_LIMIT}):")
            if (history.isEmpty()) {
                appendLine("(none)")
            } else {
                history.forEach { appendLine(it.compact()) }
            }
        }

    private fun yn(value: Boolean): String = if (value) "YES" else "NO"

    private fun score(value: Double): String = String.format(Locale.US, "%.3f", value)
}
