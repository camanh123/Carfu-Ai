package org.stypox.dicio.io.session

/**
 * V2 command-understanding model.
 *
 * The same [CommandUnderstanding] / [CanonicalCommand] instance must drive both
 * confirmation TTS and skill execution — no independent re-parse.
 */
enum class VoiceIntent {
    NAVIGATE,
    PLAY_MEDIA,
    OPEN_APP,
    CALL,
    VOLUME_UP,
    VOLUME_DOWN,
    TIME,
    UNKNOWN,
}

/**
 * Generic entity keys. Values are arbitrary strings
 * (never hardcode destinations/songs in the spine).
 */
object CommandEntityKeys {
    const val DESTINATION = "destination"
    const val QUERY = "query"
    const val PROVIDER = "provider"
    const val APP = "app"
    const val CONTACT = "contact"
    const val VOLUME_LEVEL = "volumeLevel"
}

data class CommandUnderstanding(
    val rawTranscript: String,
    val normalizedTranscript: String,
    val intent: VoiceIntent,
    val entities: Map<String, String> = emptyMap(),
    val confidence: Float = 0f,
    val completeness: SemanticCompleteness = SemanticCompleteness.UNKNOWN,
    val executable: Boolean = false,
    val command: CanonicalCommand? = null,
) {
    fun entity(key: String): String? = entities[key]?.takeIf { it.isNotBlank() }

    val destination: String? get() = entity(CommandEntityKeys.DESTINATION)
    val query: String? get() = entity(CommandEntityKeys.QUERY)
    val provider: String? get() = entity(CommandEntityKeys.PROVIDER)
    val app: String? get() = entity(CommandEntityKeys.APP)
    val contact: String? get() = entity(CommandEntityKeys.CONTACT)

    /**
     * Confirmation TTS derived from THIS object (not a second parse).
     */
    fun confirmationSpeechVi(): String? =
        command?.let { VietnameseCommandUnderstanding.confirmationSpeechVi(it) }
            ?: when (intent) {
                VoiceIntent.NAVIGATE -> destination?.let { "Đang chỉ đường đến $it" }
                VoiceIntent.PLAY_MEDIA -> {
                    val q = query ?: return null
                    val p = provider
                    if (p.isNullOrBlank()) "Đang mở $q" else "Đang mở $q trên $p"
                }
                VoiceIntent.OPEN_APP -> app?.let { "Đang mở $it" }
                VoiceIntent.CALL -> contact?.let { "Đang gọi $it" }
                VoiceIntent.VOLUME_UP -> "Đang tăng âm lượng"
                VoiceIntent.VOLUME_DOWN -> "Đang giảm âm lượng"
                VoiceIntent.TIME -> null
                VoiceIntent.UNKNOWN -> null
            }

    /** Same object also exposes execution payloads. */
    fun executionPayload(): Map<String, String> = entities

    companion object {
        fun navigate(
            raw: String,
            normalized: String,
            destination: String,
            confidence: Float = 1f,
        ): CommandUnderstanding = CommandUnderstanding(
            rawTranscript = raw,
            normalizedTranscript = normalized,
            intent = VoiceIntent.NAVIGATE,
            entities = mapOf(CommandEntityKeys.DESTINATION to destination),
            confidence = confidence,
            completeness = SemanticCompleteness.COMPLETE,
            executable = true,
            command = CanonicalCommand.Navigate(destination),
        )

        fun playMedia(
            raw: String,
            normalized: String,
            query: String,
            provider: String,
            confidence: Float = 1f,
        ): CommandUnderstanding = CommandUnderstanding(
            rawTranscript = raw,
            normalizedTranscript = normalized,
            intent = VoiceIntent.PLAY_MEDIA,
            entities = mapOf(
                CommandEntityKeys.QUERY to query,
                CommandEntityKeys.PROVIDER to provider,
            ),
            confidence = confidence,
            completeness = SemanticCompleteness.COMPLETE,
            executable = true,
            command = CanonicalCommand.PlayMedia(query, provider),
        )

        fun openApp(
            raw: String,
            normalized: String,
            appName: String,
            confidence: Float = 1f,
        ): CommandUnderstanding = CommandUnderstanding(
            rawTranscript = raw,
            normalizedTranscript = normalized,
            intent = VoiceIntent.OPEN_APP,
            entities = mapOf(CommandEntityKeys.APP to appName),
            confidence = confidence,
            completeness = SemanticCompleteness.COMPLETE,
            executable = true,
            command = CanonicalCommand.OpenApp(appName),
        )

        fun unknown(
            raw: String,
            normalized: String = raw,
            confidence: Float = 0f,
        ): CommandUnderstanding = CommandUnderstanding(
            rawTranscript = raw,
            normalizedTranscript = normalized,
            intent = VoiceIntent.UNKNOWN,
            confidence = confidence,
            completeness = SemanticCompleteness.UNKNOWN,
            executable = false,
            command = null,
        )
    }
}

/**
 * Bridge from today's [RoutedCommand] into the V2 model (legacy / fallback).
 */
fun RoutedCommand.toCommandUnderstanding(
    rawTranscript: String,
    normalizedTranscript: String = rawTranscript,
    confidence: Float = 1f,
): CommandUnderstanding {
    val intent = when (this.intent) {
        CarfuIntent.NAVIGATE_PLACE,
        CarfuIntent.NAVIGATE_HOME,
        CarfuIntent.NAVIGATE_AIRPORT,
        -> VoiceIntent.NAVIGATE
        CarfuIntent.MEDIA_PLAY,
        CarfuIntent.SEARCH,
        -> VoiceIntent.PLAY_MEDIA
        CarfuIntent.OPEN_YOUTUBE,
        CarfuIntent.OPEN_SMARTTUBE,
        CarfuIntent.OPEN_MUSICLOOP,
        CarfuIntent.OPEN_MAPS,
        CarfuIntent.OPEN_ZALO,
        CarfuIntent.OPEN_PHONE,
        -> VoiceIntent.OPEN_APP
        CarfuIntent.CALL_CONTACT,
        CarfuIntent.CALL_NUMBER,
        -> VoiceIntent.CALL
        CarfuIntent.VOLUME_UP -> VoiceIntent.VOLUME_UP
        CarfuIntent.VOLUME_DOWN -> VoiceIntent.VOLUME_DOWN
        CarfuIntent.CURRENT_TIME -> VoiceIntent.TIME
        else -> VoiceIntent.UNKNOWN
    }
    val entities = buildMap {
        place?.takeIf { it.isNotBlank() }?.let {
            put(CommandEntityKeys.DESTINATION, it)
        }
        searchQuery?.takeIf { it.isNotBlank() }?.let {
            put(CommandEntityKeys.QUERY, it)
        }
        contactName?.takeIf { it.isNotBlank() }?.let {
            put(CommandEntityKeys.CONTACT, it)
        }
        when (this@toCommandUnderstanding.intent) {
            CarfuIntent.OPEN_YOUTUBE -> put(CommandEntityKeys.APP, "YouTube")
            CarfuIntent.OPEN_SMARTTUBE -> put(CommandEntityKeys.APP, "SmartTube")
            CarfuIntent.OPEN_MUSICLOOP -> put(CommandEntityKeys.APP, "MusicLoop")
            CarfuIntent.OPEN_ZALO -> put(CommandEntityKeys.APP, "Zalo")
            CarfuIntent.OPEN_MAPS -> put(CommandEntityKeys.APP, "Maps")
            CarfuIntent.OPEN_PHONE -> put(CommandEntityKeys.APP, "Phone")
            else -> {}
        }
    }
    val command: CanonicalCommand? = when (intent) {
        VoiceIntent.NAVIGATE -> place?.let { CanonicalCommand.Navigate(it) }
        VoiceIntent.OPEN_APP -> entities[CommandEntityKeys.APP]?.let { CanonicalCommand.OpenApp(it) }
        VoiceIntent.PLAY_MEDIA -> {
            val q = entities[CommandEntityKeys.QUERY]
            if (q != null) CanonicalCommand.PlayMedia(q, entities[CommandEntityKeys.PROVIDER])
            else null
        }
        VoiceIntent.CALL -> contactName?.let { CanonicalCommand.CallContact(it) }
        VoiceIntent.VOLUME_UP -> CanonicalCommand.Volume(up = true)
        VoiceIntent.VOLUME_DOWN -> CanonicalCommand.Volume(up = false)
        VoiceIntent.TIME -> CanonicalCommand.Time
        VoiceIntent.UNKNOWN -> null
    }
    return CommandUnderstanding(
        rawTranscript = rawTranscript,
        normalizedTranscript = normalizedTranscript,
        intent = intent,
        entities = entities,
        confidence = confidence,
        completeness = if (command != null) {
            SemanticCompleteness.COMPLETE
        } else {
            SemanticCompleteness.UNKNOWN
        },
        executable = command != null,
        command = command,
    )
}
