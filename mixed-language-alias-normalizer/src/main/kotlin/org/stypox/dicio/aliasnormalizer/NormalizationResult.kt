package org.stypox.dicio.aliasnormalizer

/**
 * Pure text result. No command, no launch, no network.
 */
data class NormalizationResult(
    val originalTranscript: String,
    val normalizedTranscript: String,
    val providerDetected: String?,
    val aliasMatched: String?,
    val changed: Boolean,
)
