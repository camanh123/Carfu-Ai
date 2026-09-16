package org.stypox.dicio.aliasnormalizer

/**
 * Central, conservative provider-alias table.
 *
 * Add rows from real CARFU transcripts only. Do not grow this into a
 * general spell-checker. YouTube is intentionally absent: production already
 * understands those utterances; this module must not rewrite them.
 */
data class ProviderAlias(
    val canonicalProvider: String,
    /** Human-readable observed form, for diagnostics. */
    val observedForm: String,
    /** Folded token sequence used for matching. */
    val foldedForm: String,
)

class ProviderAliasRegistry(
    aliases: List<ProviderAlias> = defaultSmartTubeAliases(),
) {
    private val byFolded: Map<String, ProviderAlias> = aliases
        .groupBy { it.foldedForm }
        .mapValues { (_, group) -> group.first() }

    fun findExact(foldedProviderSlot: String): ProviderAlias? =
        byFolded[foldedProviderSlot.trim()]

    fun all(): List<ProviderAlias> = byFolded.values.toList()

    companion object {
        const val SMARTTUBE = "SmartTube"

        /**
         * Device-observed / requested SmartTube STT forms. Folded keys are
         * unique; diacritic variants that fold together share one row.
         */
        fun defaultSmartTubeAliases(): List<ProviderAlias> = listOf(
            alias(SMARTTUBE, "SmartTube", "smarttube"),
            alias(SMARTTUBE, "smart tube", "smart tube"),
            alias(SMARTTUBE, "smart túp", "smart tup"),
            // sờ mat túp / sờ mắt túp fold to the same key.
            alias(SMARTTUBE, "sờ mát túp", "so mat tup"),
            alias(SMARTTUBE, "sờ mát tube", "so mat tube"),
        )

        private fun alias(
            canonical: String,
            observed: String,
            folded: String,
        ): ProviderAlias = ProviderAlias(
            canonicalProvider = canonical,
            observedForm = observed,
            foldedForm = folded,
        )
    }
}
