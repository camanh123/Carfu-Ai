package org.stypox.dicio.io.session

import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight deterministic normalization for Vietnamese automotive voice commands.
 * Supports mixed Vietnamese/English app names and common phonetic STT variants.
 */
object CommandTranscriptNormalizer {

    enum class Domain {
        OPEN_APP,
        NAVIGATE,
        VOLUME_UP,
        VOLUME_DOWN,
        TIME,
        CALL,
        MEDIA,
        UNKNOWN,
    }

    data class AppTarget(
        val intent: CarfuIntent,
        val canonicalVi: String,
        val aliases: Set<String>,
    )

    private val OPEN_APP_PREFIX = Regex("""^(?:mo|bat|mo app|mo ung dung)\s+""")
    private val NAV_PREFIX = Regex(
        """^(?:chi duong(?: den| toi| ve)?|dan duong(?: den| toi| ve)?|mo ban do den|di den)\s+""",
    )
    private val NAV_PREFIXES: List<String> = listOf(
        "chi duong den",
        "chi duong toi",
        "chi duong ve",
        "dan duong den",
        "dan duong toi",
        "dan duong ve",
        "mo ban do den",
        "di den",
        "chi duong",
        "dan duong",
    ).sortedByDescending { it.length }

    private val APP_TARGETS: List<AppTarget> = listOf(
        AppTarget(
            CarfuIntent.OPEN_SMARTTUBE,
            "mở smarttube",
            setOf(
                "smarttube",
                "smart tube",
                "smarttub",
                "smart youtube",
                "smart took",
                "smart tool",
                "smart tub",
                "smarttube beta",
                "smart tube beta",
                "smark tube",
                "smarktube",
                "xem chiu",
                "xem chi u",
                "smart chiu",
            ),
        ),
        AppTarget(
            CarfuIntent.OPEN_MUSICLOOP,
            "mở musicloop",
            setOf(
                "musicloop",
                "music loop",
                "music lup",
                "music lube",
                "nhac loop",
                "nhac lup",
                "may phat nhac",
                "may nghe nhac",
                "may nghe nhac oto",
                "may phat nhac oto",
                "ung dung nhac",
            ),
        ),
        AppTarget(
            CarfuIntent.OPEN_ZALO,
            "mở zalo",
            setOf("zalo", "za lo", "za lo o"),
        ),
        AppTarget(
            CarfuIntent.OPEN_YOUTUBE,
            "mở youtube",
            setOf("youtube", "you tube", "yt"),
        ),
        AppTarget(
            CarfuIntent.OPEN_MAPS,
            "mở bản đồ",
            setOf("ban do", "maps", "google maps", "google map", "map"),
        ),
    )

    private val VOLUME_UP_PHRASES = setOf(
        "tang am luong",
        "tang am luong len",
        "tang am luong nhe",
        "tang loa",
        "tang tieng",
        "tang len",
        "to tieng",
        "to tieng len",
        "to am luong",
        "to len",
        "lon tieng",
        "lon am luong",
        "tang volume",
        "tang vol",
        "volume len",
        "volume up",
        "am luong len",
    )

    private val VOLUME_DOWN_PHRASES = setOf(
        "giam am luong",
        "giam am luong xuong",
        "giam loa",
        "giam tieng",
        "giam xuong",
        "nho tieng",
        "nho tieng xuong",
        "nho am luong",
        "nho lai",
        "giam volume",
        "giam vol",
        "volume xuong",
        "volume down",
        "am luong xuong",
    )

    private val TIME_PHRASES = setOf(
        "may gio roi",
        "may gio",
        "bay gio la may gio",
        "xem gio",
        "hien gio",
        "gio may roi",
    )

    fun detectDomain(folded: String): Domain {
        if (folded.isEmpty()) return Domain.UNKNOWN
        if (isNavigationFolded(folded)) return Domain.NAVIGATE
        if (isVolumeUp(folded)) return Domain.VOLUME_UP
        if (isVolumeDown(folded)) return Domain.VOLUME_DOWN
        if (folded in TIME_PHRASES || folded.contains("may gio")) return Domain.TIME
        if (folded.startsWith("goi")) return Domain.CALL
        if (folded.startsWith("phat nhac") || folded.startsWith("bat nhac") ||
            folded.contains("bai tiep") || folded.contains("bai truoc") ||
            folded.startsWith("tam dung nhac")
        ) {
            return Domain.MEDIA
        }
        if (VietnameseMediaCommandGrammar.isMediaCommand(folded)) {
            return Domain.MEDIA
        }
        if (OPEN_APP_PREFIX.containsMatchIn(folded) || looksLikeBareAppName(folded)) {
            return Domain.OPEN_APP
        }
        return Domain.UNKNOWN
    }

    fun normalizeForMatch(raw: String): String = VietnameseTranscript.foldForMatch(raw)

    /**
     * Token-level similarity in [0,1]. Uses normalized edit distance on folded tokens.
     */
    fun tokenSimilarity(a: String, b: String): Float {
        val left = normalizeForMatch(a)
        val right = normalizeForMatch(b)
        if (left == right) return 1.0f
        if (left.isEmpty() || right.isEmpty()) return 0.0f
        val distance = levenshtein(left, right)
        val maxLen = max(left.length, right.length)
        return 1.0f - (distance.toFloat() / maxLen.toFloat())
    }

    fun matchAppInOpenDomain(folded: String): AppTarget? {
        val remainder = openAppRemainder(folded)
        return bestAppTarget(remainder)
    }

    private val OPEN_FILLERS: List<String> = listOf("giup toi", "giup")

    /** Folded remainder after `mở` / `bật` / `mở ứng dụng`, or [folded] if none. */
    fun openAppRemainder(folded: String): String {
        var remainder = OPEN_APP_PREFIX.replace(folded, "").trim().ifBlank { folded }
        for (filler in OPEN_FILLERS) {
            if (remainder == filler) return ""
            if (remainder.startsWith("$filler ")) {
                remainder = remainder.removePrefix("$filler ").trim()
                break
            }
        }
        return remainder
    }

    /** Exact alias match only — not the 0.78 fuzzy [bestAppTarget] path. */
    fun hasExactAppAlias(foldedRemainder: String): Boolean {
        val normalized = normalizeForMatch(foldedRemainder)
        if (normalized.isEmpty()) return false
        return APP_TARGETS.any { target -> target.aliases.any { it == normalized } }
    }

    /**
     * True when [folded] is a strict prefix of a supported NAVIGATE phrase
     * (e.g. `"mo ban do"` → `"mo ban do den …"`).
     */
    fun isPrefixOfSupportedNavigation(folded: String): Boolean {
        if (folded.isEmpty()) return false
        return NAV_PREFIXES.any { nav ->
            nav.length > folded.length && nav.startsWith("$folded ")
        }
    }

    /** Destination text after a navigation prefix, or null if incomplete / not navigation. */
    fun navigationDestination(folded: String): String? {
        val matched = NAV_PREFIXES.firstOrNull { folded == it || folded.startsWith("$it ") }
            ?: return null
        if (folded == matched) return null
        return folded.removePrefix(matched).trim().ifBlank { null }
    }

    fun bestAppTarget(phrase: String): AppTarget? {
        val normalized = normalizeForMatch(phrase)
        if (normalized.isEmpty()) return null
        var best: AppTarget? = null
        var bestScore = 0.0f
        for (target in APP_TARGETS) {
            for (alias in target.aliases) {
                val score = tokenSimilarity(normalized, alias)
                if (score > bestScore) {
                    bestScore = score
                    best = target
                }
            }
        }
        return if (bestScore >= 0.78f) best else null
    }

    fun isHighConfidenceMatch(folded: String): Boolean {
        val domain = detectDomain(folded)
        return when (domain) {
            Domain.TIME -> folded in TIME_PHRASES
            Domain.VOLUME_UP -> folded in VOLUME_UP_PHRASES
            Domain.VOLUME_DOWN -> folded in VOLUME_DOWN_PHRASES
            Domain.OPEN_APP -> matchAppInOpenDomain(folded) != null
            Domain.NAVIGATE -> NAV_PREFIX.containsMatchIn(folded)
            else -> false
        }
    }

    /**
     * True when [folded] is a strict prefix of a longer known command/alias
     * (e.g. "mo smarttube" → "mo smarttube beta").
     */
    fun isPrefixAmbiguous(folded: String): Boolean {
        if (folded.isEmpty()) return true
        return knownExtendablePhrases().any { known ->
            known.length > folded.length && known.startsWith("$folded ")
        }
    }

    /**
     * Fast-partial may execute only complete, deterministic, non-extendable phrases.
     * Never navigation / free-form entities; never prefix-ambiguous OPEN_APP.
     */
    fun isSafeForFastPartial(folded: String): Boolean {
        if (folded.isEmpty()) return false
        return when (detectDomain(folded)) {
            Domain.NAVIGATE, Domain.CALL, Domain.MEDIA, Domain.UNKNOWN -> false
            Domain.TIME ->
                folded in TIME_PHRASES && !isPrefixAmbiguous(folded)
            // Same-intent volume synonyms ("… lên") must not block the canonical phrase.
            Domain.VOLUME_UP -> isVolumeUp(folded)
            Domain.VOLUME_DOWN -> isVolumeDown(folded)
            Domain.OPEN_APP ->
                matchAppInOpenDomain(folded) != null && !isPrefixAmbiguous(folded)
        }
    }

    /**
     * NO_MATCH / late-silence rescue: require a real domain match, not weak noise.
     * Navigation is allowed here only after speech has ended (endpoint/final/error).
     */
    fun isRescueEligible(folded: String): Boolean {
        if (folded.isEmpty()) return false
        return when (detectDomain(folded)) {
            Domain.UNKNOWN -> false
            Domain.NAVIGATE -> {
                // Incomplete "chi duong den" must not rescue-execute.
                val dest = navigationDestination(folded)
                !dest.isNullOrBlank() && dest.length >= 2 &&
                    dest != "den" && dest != "toi" && dest != "ve"
            }
            Domain.OPEN_APP -> matchAppInOpenDomain(folded) != null
            Domain.TIME -> folded in TIME_PHRASES || folded.contains("may gio")
            Domain.VOLUME_UP -> isVolumeUp(folded)
            Domain.VOLUME_DOWN -> isVolumeDown(folded)
            Domain.CALL, Domain.MEDIA -> isHighConfidenceMatch(folded)
        }
    }

    private fun knownExtendablePhrases(): Set<String> {
        val phrases = LinkedHashSet<String>()
        phrases += TIME_PHRASES
        phrases += VOLUME_UP_PHRASES
        phrases += VOLUME_DOWN_PHRASES
        for (target in APP_TARGETS) {
            for (alias in target.aliases) {
                phrases += alias
                phrases += "mo $alias"
                phrases += "bat $alias"
            }
        }
        return phrases
    }

    private fun isVolumeUp(folded: String): Boolean {
        if (folded in VOLUME_UP_PHRASES) return true
        if (startsWithAny(folded, "tang am", "tang loa", "tang tieng", "tang volume", "to tieng", "to am")) {
            return true
        }
        return hasVolumeKeyword(folded) &&
            tokenSimilarity(folded, "tang am luong") >= 0.78f
    }

    private fun isVolumeDown(folded: String): Boolean {
        if (folded in VOLUME_DOWN_PHRASES) return true
        if (startsWithAny(folded, "giam am", "giam loa", "giam tieng", "giam volume", "nho tieng", "nho am")) {
            return true
        }
        return hasVolumeKeyword(folded) &&
            tokenSimilarity(folded, "giam am luong") >= 0.78f
    }

    private fun hasVolumeKeyword(folded: String): Boolean =
        folded.contains("am luong") ||
            folded.contains("volume") ||
            folded.contains(" loa") ||
            folded.startsWith("loa ") ||
            folded.contains("tieng")

    private fun looksLikeBareAppName(folded: String): Boolean =
        APP_TARGETS.any { target -> target.aliases.any { folded == it || folded.startsWith("$it ") } }

    private fun isNavigationFolded(folded: String): Boolean =
        NAV_PREFIXES.any { folded == it || folded.startsWith("$it ") } ||
            NAV_PREFIX.containsMatchIn(folded)

    private fun startsWithAny(folded: String, vararg prefixes: String): Boolean =
        prefixes.any { folded.startsWith(it) }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)
        for (i in a.indices) {
            curr[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                curr[j + 1] = min(
                    min(curr[j] + 1, prev[j + 1] + 1),
                    prev[j] + cost,
                )
            }
            for (j in prev.indices) prev[j] = curr[j]
        }
        return prev[b.length]
    }
}
