package org.stypox.dicio.io.session

import org.stypox.dicio.skills.carfu.nlu.NavigationAddressNormalizer
import org.stypox.dicio.skills.carfu.nlu.NavigationCommitPolicy

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

    private val NAV_PARTICLES = setOf("den", "toi", "ve")

    private val OPEN_PREFIX = Regex("""^(?:mo|bat|mo app|mo ung dung)\s+""")

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
        // Navigation command prefixes must win over Media's generic "tìm …" grammar.
        // Media grammar itself is unchanged.
        if (NavigationAddressNormalizer.looksLikeNavigationCommand(trimmed)) {
            understandNavigate(trimmed, folded, sessionId, candidateIndex, recognizerConfidence)
                ?.let { return it }
        }
        if (isUnsupportedPlaceOrNearbyQuery(trimmed, folded)) {
            return UnderstandingResult.unknown(
                sessionId = sessionId,
                raw = trimmed,
                normalized = folded,
                candidateIndex = candidateIndex,
                recognizerConfidence = recognizerConfidence,
                reason = "unsupported_place_search",
            )
        }
        mediaResult(trimmed, folded, sessionId, candidateIndex, recognizerConfidence)
            ?.let { return it }
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

    /**
     * Pick the command that should lock for this candidate set.
     *
     * Complete PlayMedia always wins over OpenApp YouTube. An in-progress PlayMedia
     * query must not fall through to OpenApp("YouTube") (YouTube home). Bare
     * "Mở YouTube" with no media candidate is unchanged.
     */
    fun selectDecision(ranked: List<UnderstandingResult>): UnderstandingResult? {
        if (ranked.isEmpty()) return null
        val mediaComplete = ranked.firstOrNull { result ->
            result.completeness == SemanticCompleteness.COMPLETE &&
                result.intent == VoiceIntent.PLAY_MEDIA &&
                hasPlayMediaQuery(result)
        }
        if (mediaComplete != null) return mediaComplete
        val best = ranked.first()
        if (isExactCatalogOpenAppYouTube(best) && ranked.any { hasPlayMediaQuery(it) }) {
            return ranked.firstOrNull { hasPlayMediaQuery(it) } ?: best
        }
        return best
    }

    fun hasPlayMediaQuery(result: UnderstandingResult): Boolean {
        if (result.intent != VoiceIntent.PLAY_MEDIA) return false
        val query = result.query
            ?: (result.command as? CanonicalCommand.PlayMedia)?.query
            ?: return false
        return query.trim().length >= 2
    }

    fun isExactCatalogOpenAppYouTube(result: UnderstandingResult): Boolean {
        if (!isExactCatalogOpenApp(result)) return false
        val name = (result.command as? CanonicalCommand.OpenApp)?.appName ?: return false
        return VietnameseTranscript.foldForMatch(name) == "youtube"
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
        val normalized = NavigationAddressNormalizer.parse(raw)
        if (!normalized.matchedCommand) return null
        val destRaw = normalized.destination
        val destFolded = normalized.destinationFolded
        if (destRaw.isBlank() || destFolded.isEmpty() || isNavParticleOnly(destFolded)) {
            return UnderstandingResult.incomplete(
                sessionId = sessionId,
                raw = raw,
                normalized = folded,
                intent = VoiceIntent.NAVIGATE,
                reason = if (destRaw.isBlank()) "nav_missing_destination" else "nav_incomplete_particle",
                candidateIndex = candidateIndex,
                recognizerConfidence = recognizerConfidence,
            )
        }
        val command = CanonicalCommand.Navigate(destination = destRaw)
        if (NavigationCommitPolicy.isIncompleteDestination(destRaw)) {
            return UnderstandingResult(
                sessionId = sessionId,
                rawTranscript = raw,
                normalizedTranscript = folded,
                intent = VoiceIntent.NAVIGATE,
                entities = mapOf(CommandEntityKeys.DESTINATION to destRaw),
                confidence = 0.6f,
                completeness = SemanticCompleteness.INCOMPLETE,
                executable = false,
                command = command,
                candidateIndex = candidateIndex,
                recognizerConfidence = recognizerConfidence,
                reason = "nav_incomplete_head",
            )
        }
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

    private fun mediaResult(
        raw: String,
        folded: String,
        sessionId: Long,
        candidateIndex: Int,
        recognizerConfidence: Float,
    ): UnderstandingResult? {
        val analysis = VietnameseMediaCommandGrammar.parse(raw, folded) ?: return null
        if (!analysis.complete) {
            return UnderstandingResult.incomplete(
                sessionId = sessionId,
                raw = raw,
                normalized = folded,
                intent = VoiceIntent.PLAY_MEDIA,
                reason = analysis.reason,
                candidateIndex = candidateIndex,
                recognizerConfidence = recognizerConfidence,
                entities = buildMap {
                    if (analysis.queryRaw.isNotBlank()) {
                        put(CommandEntityKeys.QUERY, analysis.queryRaw)
                    } else if (analysis.queryFolded.isNotBlank()) {
                        put(CommandEntityKeys.QUERY, analysis.queryFolded)
                    }
                    analysis.providerLabel?.let { put(CommandEntityKeys.PROVIDER, it) }
                },
            )
        }
        val queryRaw = analysis.queryRaw.trim()
        if (queryRaw.isBlank()) {
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
        val providerLabel = analysis.providerLabel?.trim().orEmpty()
        val command = CanonicalCommand.PlayMedia(
            query = queryRaw,
            provider = providerLabel.ifBlank { null },
        )
        return UnderstandingResult(
            sessionId = sessionId,
            rawTranscript = raw,
            normalizedTranscript = folded,
            intent = VoiceIntent.PLAY_MEDIA,
            entities = buildMap {
                put(CommandEntityKeys.QUERY, queryRaw)
                if (providerLabel.isNotBlank()) put(CommandEntityKeys.PROVIDER, providerLabel)
            },
            confidence = 0.92f,
            completeness = SemanticCompleteness.COMPLETE,
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
        if (VietnameseMediaCommandGrammar.isMediaCommand(folded)) return null

        val remainderFolded = CommandTranscriptNormalizer.openAppRemainder(folded)
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

    /**
     * Exact catalog OPEN_APP (KNOWN_APP_LABELS or exact APP_TARGETS alias).
     * Fuzzy 0.78 [CommandTranscriptNormalizer.bestAppTarget] matches are not exact.
     */
    fun isExactCatalogOpenApp(result: UnderstandingResult): Boolean {
        val command = result.command as? CanonicalCommand.OpenApp ?: return false
        if (result.completeness != SemanticCompleteness.COMPLETE) return false
        if (result.reason != "open_catalog") return false
        if (command.appName.isBlank()) return false
        val remainder = CommandTranscriptNormalizer.openAppRemainder(result.normalizedTranscript)
        if (remainder.isEmpty()) return false
        if (KNOWN_APP_LABELS.containsKey(remainder)) return true
        return CommandTranscriptNormalizer.hasExactAppAlias(remainder)
    }

    fun isKnownPlayMediaProvider(provider: String?): Boolean =
        VietnameseMediaCommandGrammar.isKnownProviderLabel(provider)

    /**
     * Nearby / place-find phrasing without a media noun or provider.
     * CARFU has no PlaceSearch skill yet — these must not be PlayMedia or SEARCH.
     */
    fun isUnsupportedPlaceOrNearbyQuery(
        raw: String,
        folded: String = VietnameseTranscript.foldForMatch(raw),
    ): Boolean {
        if (folded.isBlank()) return false
        if (NavigationAddressNormalizer.looksLikeNavigationCommand(raw)) return false
        val tokens = folded.split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty() || tokens.first() != "tim") return false
        val mediaNouns = setOf("bai", "hat", "nhac", "video", "clip")
        if (tokens.any { it in mediaNouns }) return false
        if (tokens.any { it in setOf("youtube", "yt", "smarttube", "musicloop") }) return false
        val politeFind = tokens.size >= 3 &&
            (
                tokens.take(3) == listOf("tim", "cho", "toi") ||
                    tokens.take(3) == listOf("tim", "giup", "toi") ||
                    tokens.take(3) == listOf("tim", "cho", "minh")
                )
        val nearby = tokens.contains("gan")
        return (politeFind || nearby) && tokens.size >= 4
    }

    private fun resolveKnownApp(foldedRemainder: String): String? {
        KNOWN_APP_LABELS[foldedRemainder]?.let { return it }
        // Conservative fuzzy via catalog only (threshold inside normalizer).
        return null
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
