package org.stypox.dicio.aliasnormalizer

/**
 * One experimental catalog. Matching code must not hard-code provider names.
 *
 * Spotify is intentionally absent: a "spotify" STT token must stay UNKNOWN.
 */
data class ProviderEntity(
    val canonicalName: String,
    val provenAliases: List<String>,
) {
    val foldedCanonical: String = TranscriptFolder.fold(canonicalName)
    val spacedCanonical: String = TranscriptFolder.fold(splitCamelName(canonicalName))
    val foldedAliases: List<String> = provenAliases.map { TranscriptFolder.fold(it) }.distinct()
}

class ProviderEntityCatalog(
    smartTubeAliases: List<ProviderAlias> = ProviderAliasRegistry.defaultSmartTubeAliases(),
) {
    val entities: List<ProviderEntity> = listOf(
        ProviderEntity(
            canonicalName = SMARTTUBE,
            provenAliases = smartTubeAliases.map { it.observedForm }.distinct(),
        ),
        ProviderEntity(
            canonicalName = YOUTUBE,
            provenAliases = listOf("YouTube", "you tube", "yt"),
        ),
        ProviderEntity(
            canonicalName = MUSICLOOP,
            provenAliases = listOf("MusicLoop", "music loop", "musicloop"),
        ),
    )

    fun all(): List<ProviderEntity> = entities

    companion object {
        const val SMARTTUBE = "SmartTube"
        const val YOUTUBE = "YouTube"
        const val MUSICLOOP = "MusicLoop"
    }
}

internal fun splitCamelName(name: String): String =
    name.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
