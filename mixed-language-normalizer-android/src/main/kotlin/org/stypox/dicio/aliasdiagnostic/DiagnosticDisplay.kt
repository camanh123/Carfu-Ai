package org.stypox.dicio.aliasdiagnostic

import org.stypox.dicio.aliasnormalizer.DefaultProviderAliasNormalizer
import org.stypox.dicio.aliasnormalizer.NormalizationResult
import org.stypox.dicio.aliasnormalizer.ProviderAliasNormalizer

/**
 * Maps [NormalizationResult] from the existing JVM module onto the
 * diagnostic UI fields. Does not duplicate alias logic.
 */
data class DiagnosticSnapshot(
    val status: String,
    val partialTranscript: String,
    val rawStt: String,
    val normalized: String,
    val provider: String,
    val aliasMatched: String,
    val changed: Boolean,
) {
    fun format(): String = buildString {
        appendLine("STATUS: $status")
        appendLine("PARTIAL: $partialTranscript")
        appendLine("RAW STT: $rawStt")
        appendLine("NORMALIZED: $normalized")
        appendLine("PROVIDER: $provider")
        appendLine("ALIAS MATCHED: $aliasMatched")
        appendLine("CHANGED: $changed")
        appendLine("LOCALE: ${AliasDiagnosticPolicy.SPEECH_LOCALE}")
        appendLine("PRODUCTION_WIRED: ${AliasDiagnosticPolicy.PRODUCTION_WIRED}")
    }
}

object DiagnosticDisplay {
    fun defaultNormalizer(): ProviderAliasNormalizer = DefaultProviderAliasNormalizer()

    fun of(
        normalizer: ProviderAliasNormalizer,
        rawTranscript: String,
        partialTranscript: String = "",
        status: String,
    ): DiagnosticSnapshot = from(
        result = normalizer.normalize(rawTranscript),
        partialTranscript = partialTranscript,
        status = status,
    )

    fun from(
        result: NormalizationResult,
        partialTranscript: String = "",
        status: String,
    ): DiagnosticSnapshot = DiagnosticSnapshot(
        status = status,
        partialTranscript = partialTranscript,
        rawStt = result.originalTranscript,
        normalized = result.normalizedTranscript,
        provider = result.providerDetected.orEmpty(),
        aliasMatched = result.aliasMatched.orEmpty(),
        changed = result.changed,
    )
}
