package org.stypox.dicio.io.session

/**
 * Deterministic Vietnamese command understanding (Phase 2).
 *
 * RAW STT → normalize (internal) → candidate evaluation → semantic completeness →
 * intent + entities → CanonicalCommand.
 *
 * Does not mutate live UI transcripts. Does not launch apps / Maps (Phase 3).
 */
object VietnameseCommandUnderstanding {
    private val WHITESPACE = Regex("\\s+")

    /**
     * Longest folded navigation prefixes first. Trailing space required so
     * "chi duong den" alone is incomplete (no destination token).
     */
    private val NAV_PREFIX_FOLDED: List<String> = listOf(
        "chi duong den ",
        "chi duong toi ",
        "chi duong ve ",
        "dan duong den ",
        "dan duong toi ",
        "dan duong ve ",
        "mo ban do den ",
        "di den ",
        "di toi ",
        "chi duong ",
        "dan duong ",
    )

    private val NAV_PARTICLES = setOf("den", "toi", "ve")

    private val OPEN_PREFIX = Regex("""^(?:mo|bat|mo app|mo ung dung)\s+""")

    /** "mở bài <query> trên <provider>" — capture original text via trailing alignment. */
    private val PLAY_MEDIA_FOLDED = Regex("""^mo bai (.+) tren (.+)$""")

    private val KNOWN_APP_LABELS: Map<String, String> = mapOf(
        "youtube" to "YouTube",
        "you tube" to "YouTube",
        "yt" to "YouTube",
        "musicloop" to "MusicLoop",
        "music loop" to "MusicLoop",
        "music look" to "MusicLoop",
        "music lup" to "MusicLoop",
        "music lube" to "MusicLoop",
        "smarttube" to "SmartTube",
        "smart tube" to "SmartTube",
        "zalo" to "Zalo",
        "ban do" to "Maps",
        "maps" to "Maps",
        "google maps" to "Maps",
        "chrome" to "Chrome",
        "google chrome" to "Chrome",
    )

    private val PROVIDER_LABELS: Map<String, String> = mapOf(
        "youtube" to "YouTube",
        "you tube" to "YouTube",
        "yt" to "YouTube",
        "smarttube" to "SmartTube",
        "smart tube" to "SmartTube",
        "musicloop" to "MusicLoop",
        "music loop" to "MusicLoop",
    )

    fun understand(
        raw: String,
        sessionId: Long = 0L,
        candidateIndex: Int = 0,
        recognizerConfidence: Float = 1f,
    ): UnderstandingResult {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return UnderstandingResult.unknown(
                sessionId = sessionId,
                raw = trimmed,
                normalized = "",
                candidateIndex = candidateIndex,
                recognizerConfidence = recognizerConfidence,
                reason = "weak_or_empty",
            )
        }
        val folded = VietnameseTranscript.foldForMatch(trimmed)
        // Bare open verbs are incomplete (not UNKNOWN), even if too short for STT submit.
        if (folded == "mo" || folded == "bat") {
            return UnderstandingResult.incomplete(
                sessionId = sessionId,
                raw = trimmed,
                normalized = folded,
                intent = VoiceIntent.OPEN_APP,
                reason = "open_missing_app",
                candidateIndex = candidateIndex,
                recognizerConfidence = recognizerConfidence,
            )
        }
        if (VietnameseTranscript.isTooWeakToSubmit(trimmed)) {
            return UnderstandingResult.unknown(
                sessionId = sessionId,
                raw = trimmed,
                normalized = folded,
                candidateIndex = candidateIndex,
                recognizerConfidence = recognizerConfidence,
                reason = "weak_or_empty",
            )
        }

        understandPlayMedia(trimmed, folded, sessionId, candidateIndex, recognizerConfidence)
            ?.let { return it }
        understandNavigate(trimmed, folded, sessionId, candidateIndex, recognizerConfidence)
            ?.let { return it }
        understandOpenApp(trimmed, folded, sessionId, candidateIndex, recognizerConfidence)
            ?.let { return it }
        understandVolume(trimmed, folded, sessionId, candidateIndex, recognizerConfidence)
            ?.let { return it }
        understandTime(trimmed, folded, sessionId, candidateIndex, recognizerConfidence)
            ?.let { return it }
        understandCall(trimmed, folded, sessionId, candidateIndex, recognizerConfidence)
            ?.let { return it }

        return UnderstandingResult.unknown(
            sessionId = sessionId,
            raw = trimmed,
            normalized = folded,
            candidateIndex = candidateIndex,
            recognizerConfidence = recognizerConfidence,
            reason = "no_domain",
        )
    }

    fun rankCandidates(
        sessionId: Long,
        candidates: List<Pair<String, Float>>,
    ): List<UnderstandingResult> {
        if (candidates.isEmpty()) return emptyList()
        return candidates.mapIndexed { index, (text, conf) ->
            understand(
                raw = text,
                sessionId = sessionId,
                candidateIndex = index,
                recognizerConfidence = conf,
            )
        }.sortedWith(candidateComparator).reversed()
    }

    /** True when [candidate] is a stronger final than [current] (upgrade allowed). */
    fun isStronger(candidate: UnderstandingResult, current: UnderstandingResult): Boolean {
        return candidateComparator.compare(candidate, current) > 0
    }

    private val candidateComparator = Comparator<UnderstandingResult> { a, b ->
        compareValuesBy(
            a,
            b,
            { completenessRank(it.completeness) },
            { if (it.executable) 1 else 0 },
            { entityRichness(it) },
            { it.confidence },
            { it.recognizerConfidence },
            { it.rawTranscript.length },
            { -it.candidateIndex },
        )
    }

    private fun completenessRank(c: SemanticCompleteness): Int = when (c) {
        SemanticCompleteness.COMPLETE -> 2
        SemanticCompleteness.INCOMPLETE -> 1
        SemanticCompleteness.UNKNOWN -> 0
    }

    private fun entityRichness(r: UnderstandingResult): Int {
        val cmd = r.command ?: return 0
        return when (cmd) {
            is CanonicalCommand.Navigate -> cmd.destination.length
            is CanonicalCommand.PlayMedia ->
                cmd.query.length + (cmd.provider?.length ?: 0)
            is CanonicalCommand.OpenApp -> cmd.appName.length
            is CanonicalCommand.CallContact -> cmd.contactName.length
            else -> 1
        }
    }

    private fun understandNavigate(
        raw: String,
        folded: String,
        sessionId: Long,
        candidateIndex: Int,
        recognizerConfidence: Float,
    ): UnderstandingResult? {
        val prefix = NAV_PREFIX_FOLDED.firstOrNull { folded.startsWith(it) } ?: run {
            // Exact prefix with no destination (no trailing space match).
            val bare = NAV_PREFIX_FOLDED.map { it.trimEnd() }
                .firstOrNull { folded == it }
            if (bare != null) {
                return UnderstandingResult.incomplete(
                    sessionId = sessionId,
                    raw = raw,
                    normalized = folded,
                    intent = VoiceIntent.NAVIGATE,
                    reason = "nav_missing_destination",
                    candidateIndex = candidateIndex,
                    recognizerConfidence = recognizerConfidence,
                )
            }
            return null
        }
        val destFolded = folded.removePrefix(prefix).trim()
        if (destFolded.isEmpty() || isNavParticleOnly(destFolded)) {
            return UnderstandingResult.incomplete(
                sessionId = sessionId,
                raw = raw,
                normalized = folded,
                intent = VoiceIntent.NAVIGATE,
                reason = "nav_incomplete_particle",
                candidateIndex = candidateIndex,
                recognizerConfidence = recognizerConfidence,
            )
        }
        val destRaw = extractTrailingWords(
            raw,
            destFolded.split(' ').filter { it.isNotEmpty() }.size,
        )
        if (destRaw.isBlank() || isNavParticleOnly(VietnameseTranscript.foldForMatch(destRaw))) {
            return UnderstandingResult.incomplete(
                sessionId = sessionId,
                raw = raw,
                normalized = folded,
                intent = VoiceIntent.NAVIGATE,
                reason = "nav_invalid_destination",
                candidateIndex = candidateIndex,
                recognizerConfidence = recognizerConfidence,
            )
        }
        val command = CanonicalCommand.Navigate(destination = destRaw)
        return UnderstandingResult(
            sessionId = sessionId,
            rawTranscript = raw,
            normalizedTranscript = folded,
            intent = VoiceIntent.NAVIGATE,
            entities = mapOf(CommandEntityKeys.DESTINATION to destRaw),
            confidence = 0.95f,
            completeness = SemanticCompleteness.COMPLETE,
            executable = true,
            command = command,
            candidateIndex = candidateIndex,
            recognizerConfidence = recognizerConfidence,
            reason = "nav_complete",
        )
    }

    private fun understandPlayMedia(
        raw: String,
        folded: String,
        sessionId: Long,
        candidateIndex: Int,
        recognizerConfidence: Float,
    ): UnderstandingResult? {
        // Incomplete: "mở bài trên YouTube" / "mo bai tren youtube"
        if (Regex("""^mo bai tren .+$""").matches(folded)) {
            val provider = resolveProviderLabel(
                folded.removePrefix("mo bai tren ").trim(),
            )
            return UnderstandingResult.incomplete(
                sessionId = sessionId,
                raw = raw,
                normalized = folded,
                intent = VoiceIntent.PLAY_MEDIA,
                reason = "media_missing_query",
                candidateIndex = candidateIndex,
                recognizerConfidence = recognizerConfidence,
                entities = provider?.let { mapOf(CommandEntityKeys.PROVIDER to it) }
                    ?: emptyMap(),
            )
        }
        if (folded == "mo bai" || folded.startsWith("mo bai ") && !folded.contains(" tren ")) {
            // Partial media without provider — incomplete, not OPEN_APP.
            if (folded == "mo bai" || folded.removePrefix("mo bai ").trim().isNotEmpty()) {
                return UnderstandingResult.incomplete(
                    sessionId = sessionId,
                    raw = raw,
                    normalized = folded,
                    intent = VoiceIntent.PLAY_MEDIA,
                    reason = "media_incomplete",
                    candidateIndex = candidateIndex,
                    recognizerConfidence = recognizerConfidence,
                )
            }
        }
        val match = PLAY_MEDIA_FOLDED.matchEntire(folded) ?: return null
        val queryFolded = match.groupValues[1].trim()
        val providerFolded = match.groupValues[2].trim()
        if (queryFolded.isEmpty()) {
            return UnderstandingResult.incomplete(
                sessionId = sessionId,
                raw = raw,
                normalized = folded,
                intent = VoiceIntent.PLAY_MEDIA,
                reason = "media_empty_query",
                candidateIndex = candidateIndex,
                recognizerConfidence = recognizerConfidence,
            )
        }
        val providerLabel = resolveProviderLabel(providerFolded)
            ?: titleCaseWords(extractTrailingWords(raw, providerFolded.split(' ').size))
        val queryRaw = extractMediaQueryRaw(raw, queryFolded, providerFolded)
        if (queryRaw.isBlank()) {
            return UnderstandingResult.incomplete(
                sessionId = sessionId,
                raw = raw,
                normalized = folded,
                intent = VoiceIntent.PLAY_MEDIA,
                reason = "media_query_extract_failed",
                candidateIndex = candidateIndex,
                recognizerConfidence = recognizerConfidence,
            )
        }
        val command = CanonicalCommand.PlayMedia(query = queryRaw, provider = providerLabel)
        return UnderstandingResult(
            sessionId = sessionId,
            rawTranscript = raw,
            normalizedTranscript = folded,
            intent = VoiceIntent.PLAY_MEDIA,
            entities = buildMap {
                put(CommandEntityKeys.QUERY, queryRaw)
                put(CommandEntityKeys.PROVIDER, providerLabel)
            },
            confidence = 0.92f,
            completeness = SemanticCompleteness.COMPLETE,
            // Phase 3 executes providers; Phase 2 marks structurally executable.
            executable = true,
            command = command,
            candidateIndex = candidateIndex,
            recognizerConfidence = recognizerConfidence,
            reason = "media_complete",
        )
    }

    private fun understandOpenApp(
        raw: String,
        folded: String,
        sessionId: Long,
        candidateIndex: Int,
        recognizerConfidence: Float,
    ): UnderstandingResult? {
        if (folded == "mo" || folded == "bat") {
            return UnderstandingResult.incomplete(
                sessionId = sessionId,
                raw = raw,
                normalized = folded,
                intent = VoiceIntent.OPEN_APP,
                reason = "open_missing_app",
                candidateIndex = candidateIndex,
                recognizerConfidence = recognizerConfidence,
            )
        }
        if (!OPEN_PREFIX.containsMatchIn(folded) &&
            CommandTranscriptNormalizer.detectDomain(folded) !=
            CommandTranscriptNormalizer.Domain.OPEN_APP
        ) {
            return null
        }
        // Do not steal media forms.
        if (folded.startsWith("mo bai ")) return null

        val remainderFolded = OPEN_PREFIX.replace(folded, "").trim().ifBlank { folded }
        if (remainderFolded.isEmpty()) {
            return UnderstandingResult.incomplete(
                sessionId = sessionId,
                raw = raw,
                normalized = folded,
                intent = VoiceIntent.OPEN_APP,
                reason = "open_missing_app",
                candidateIndex = candidateIndex,
                recognizerConfidence = recognizerConfidence,
            )
        }

        val catalog = resolveKnownApp(remainderFolded)
            ?: CommandTranscriptNormalizer.bestAppTarget(remainderFolded)?.let { target ->
                when (target.intent) {
                    CarfuIntent.OPEN_MUSICLOOP -> "MusicLoop"
                    CarfuIntent.OPEN_YOUTUBE -> "YouTube"
                    CarfuIntent.OPEN_SMARTTUBE -> "SmartTube"
                    CarfuIntent.OPEN_ZALO -> "Zalo"
                    CarfuIntent.OPEN_MAPS -> "Maps"
                    else -> target.canonicalVi.removePrefix("mở ").trim()
                        .replaceFirstChar { it.uppercase() }
                }
            }

        val appName = catalog
            ?: extractTrailingWords(
                raw,
                remainderFolded.split(' ').filter { it.isNotEmpty() }.size,
            ).ifBlank { titleCaseWords(remainderFolded) }

        if (appName.isBlank()) {
            return UnderstandingResult.incomplete(
                sessionId = sessionId,
                raw = raw,
                normalized = folded,
                intent = VoiceIntent.OPEN_APP,
                reason = "open_empty_app",
                candidateIndex = candidateIndex,
                recognizerConfidence = recognizerConfidence,
            )
        }

        val confidence = if (catalog != null) 0.9f else 0.75f
        val command = CanonicalCommand.OpenApp(appName = appName)
        return UnderstandingResult(
            sessionId = sessionId,
            rawTranscript = raw,
            normalizedTranscript = folded,
            intent = VoiceIntent.OPEN_APP,
            entities = mapOf(CommandEntityKeys.APP to appName),
            confidence = confidence,
            completeness = SemanticCompleteness.COMPLETE,
            executable = true,
            command = command,
            candidateIndex = candidateIndex,
            recognizerConfidence = recognizerConfidence,
            reason = if (catalog != null) "open_catalog" else "open_generic",
        )
    }

    private fun understandVolume(
        raw: String,
        folded: String,
        sessionId: Long,
        candidateIndex: Int,
        recognizerConfidence: Float,
    ): UnderstandingResult? {
        val domain = CommandTranscriptNormalizer.detectDomain(folded)
        return when (domain) {
            CommandTranscriptNormalizer.Domain.VOLUME_UP -> UnderstandingResult(
                sessionId = sessionId,
                rawTranscript = raw,
                normalizedTranscript = folded,
                intent = VoiceIntent.VOLUME_UP,
                confidence = 0.95f,
                completeness = SemanticCompleteness.COMPLETE,
                executable = true,
                command = CanonicalCommand.Volume(up = true),
                candidateIndex = candidateIndex,
                recognizerConfidence = recognizerConfidence,
                reason = "volume_up",
            )
            CommandTranscriptNormalizer.Domain.VOLUME_DOWN -> UnderstandingResult(
                sessionId = sessionId,
                rawTranscript = raw,
                normalizedTranscript = folded,
                intent = VoiceIntent.VOLUME_DOWN,
                confidence = 0.95f,
                completeness = SemanticCompleteness.COMPLETE,
                executable = true,
                command = CanonicalCommand.Volume(up = false),
                candidateIndex = candidateIndex,
                recognizerConfidence = recognizerConfidence,
                reason = "volume_down",
            )
            else -> null
        }
    }

    private fun understandTime(
        raw: String,
        folded: String,
        sessionId: Long,
        candidateIndex: Int,
        recognizerConfidence: Float,
    ): UnderstandingResult? {
        if (CommandTranscriptNormalizer.detectDomain(folded) !=
            CommandTranscriptNormalizer.Domain.TIME
        ) {
            return null
        }
        return UnderstandingResult(
            sessionId = sessionId,
            rawTranscript = raw,
            normalizedTranscript = folded,
            intent = VoiceIntent.TIME,
            confidence = 0.95f,
            completeness = SemanticCompleteness.COMPLETE,
            executable = true,
            command = CanonicalCommand.Time,
            candidateIndex = candidateIndex,
            recognizerConfidence = recognizerConfidence,
            reason = "time",
        )
    }

    private fun understandCall(
        raw: String,
        folded: String,
        sessionId: Long,
        candidateIndex: Int,
        recognizerConfidence: Float,
    ): UnderstandingResult? {
        if (!folded.startsWith("goi ")) return null
        if (folded == "goi" || folded == "goi cho") {
            return UnderstandingResult.incomplete(
                sessionId = sessionId,
                raw = raw,
                normalized = folded,
                intent = VoiceIntent.CALL,
                reason = "call_missing_contact",
                candidateIndex = candidateIndex,
                recognizerConfidence = recognizerConfidence,
            )
        }
        val nameFolded = folded.removePrefix("goi cho ").removePrefix("goi ").trim()
        if (nameFolded.isEmpty()) return null
        val nameRaw = extractTrailingWords(
            raw,
            nameFolded.split(' ').filter { it.isNotEmpty() }.size,
        )
        return UnderstandingResult(
            sessionId = sessionId,
            rawTranscript = raw,
            normalizedTranscript = folded,
            intent = VoiceIntent.CALL,
            entities = mapOf(CommandEntityKeys.CONTACT to nameRaw),
            confidence = 0.85f,
            completeness = SemanticCompleteness.COMPLETE,
            executable = true,
            command = CanonicalCommand.CallContact(contactName = nameRaw),
            candidateIndex = candidateIndex,
            recognizerConfidence = recognizerConfidence,
            reason = "call_contact",
        )
    }

    fun isNavParticleOnly(foldedDest: String): Boolean {
        val d = foldedDest.trim()
        return d in NAV_PARTICLES
    }

    /**
     * Map a complete [CanonicalCommand] to today's [RoutedCommand] when Phase-1/2
     * execution already supports it. Returns null for Phase-3-only commands
     * (generic OPEN_APP, PLAY_MEDIA provider launch).
     */
    fun toExecutableRoutedCommand(command: CanonicalCommand): RoutedCommand? = when (command) {
        is CanonicalCommand.Navigate -> RoutedCommand(
            intent = CarfuIntent.NAVIGATE_PLACE,
            canonicalVi = "chỉ đường đến ${command.destination}",
            skillId = "navigation",
            place = command.destination,
        )
        is CanonicalCommand.OpenApp -> when (command.appName.lowercase()) {
            "youtube" -> RoutedCommand(
                CarfuIntent.OPEN_YOUTUBE, "mở youtube", "open",
            )
            "musicloop" -> RoutedCommand(
                CarfuIntent.OPEN_MUSICLOOP, "mở musicloop", "open",
            )
            "smarttube" -> RoutedCommand(
                CarfuIntent.OPEN_SMARTTUBE, "mở smarttube", "open",
            )
            "zalo" -> RoutedCommand(
                CarfuIntent.OPEN_ZALO, "mở zalo", "open",
            )
            "maps", "google maps" -> RoutedCommand(
                CarfuIntent.OPEN_MAPS, "mở bản đồ", "open",
            )
            "phone", "điện thoại", "dien thoai" -> RoutedCommand(
                CarfuIntent.OPEN_PHONE, "mở điện thoại", "open",
            )
            else -> null // generic installed-app launch → Phase 3
        }
        is CanonicalCommand.Volume -> if (command.up) {
            RoutedCommand(CarfuIntent.VOLUME_UP, "tăng âm lượng", "volume")
        } else {
            RoutedCommand(CarfuIntent.VOLUME_DOWN, "giảm âm lượng", "volume")
        }
        CanonicalCommand.Time -> RoutedCommand(
            CarfuIntent.CURRENT_TIME, "mấy giờ rồi", "current_time",
        )
        is CanonicalCommand.CallContact -> RoutedCommand(
            intent = CarfuIntent.CALL_CONTACT,
            canonicalVi = "gọi cho ${command.contactName}",
            skillId = "telephone",
            contactName = command.contactName,
        )
        is CanonicalCommand.PlayMedia -> null // provider execution → Phase 3
    }

    fun confirmationSpeechVi(command: CanonicalCommand): String? = when (command) {
        is CanonicalCommand.Navigate -> "Đang chỉ đường đến ${command.destination}"
        is CanonicalCommand.OpenApp -> "Đang mở ${command.appName}"
        is CanonicalCommand.PlayMedia -> {
            val p = command.provider
            if (p.isNullOrBlank()) "Đang mở ${command.query}"
            else "Đang mở ${command.query} trên $p"
        }
        is CanonicalCommand.Volume ->
            if (command.up) "Đang tăng âm lượng" else "Đang giảm âm lượng"
        CanonicalCommand.Time -> null
        is CanonicalCommand.CallContact -> "Đang gọi ${command.contactName}"
    }

    private fun resolveKnownApp(foldedRemainder: String): String? {
        KNOWN_APP_LABELS[foldedRemainder]?.let { return it }
        // Conservative fuzzy via catalog only (threshold inside normalizer).
        return null
    }

    private fun resolveProviderLabel(folded: String): String? =
        PROVIDER_LABELS[folded.trim()]

    private fun extractMediaQueryRaw(
        raw: String,
        queryFolded: String,
        providerFolded: String,
    ): String {
        val rawWords = WHITESPACE.split(raw.trim()).filter { it.isNotEmpty() }
        val queryWordCount = queryFolded.split(' ').filter { it.isNotEmpty() }.size
        val providerWordCount = providerFolded.split(' ').filter { it.isNotEmpty() }.size
        // Expect: mở bài <query...> trên <provider...>
        if (rawWords.size >= 2 + queryWordCount + 1 + providerWordCount) {
            val queryStart = 2 // after "Mở bài"
            val queryEnd = queryStart + queryWordCount
            return rawWords.subList(queryStart, queryEnd).joinToString(" ")
        }
        return titleCaseWords(queryFolded)
    }

    internal fun extractTrailingWords(raw: String, wordCount: Int): String {
        if (wordCount <= 0) return raw.trim()
        val rawWords = WHITESPACE.split(raw.trim()).filter { it.isNotEmpty() }
        if (rawWords.isEmpty()) return raw.trim()
        if (rawWords.size <= wordCount) return rawWords.joinToString(" ")
        return rawWords.takeLast(wordCount).joinToString(" ")
    }

    private fun titleCaseWords(folded: String): String =
        folded.split(' ').filter { it.isNotEmpty() }.joinToString(" ") { w ->
            w.replaceFirstChar { c -> c.uppercase() }
        }
}
