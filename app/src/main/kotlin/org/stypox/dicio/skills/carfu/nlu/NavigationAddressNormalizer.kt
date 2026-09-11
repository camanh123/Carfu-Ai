package org.stypox.dicio.skills.carfu.nlu

import org.stypox.dicio.io.session.VietnameseCommandUnderstanding
import org.stypox.dicio.io.session.VietnameseTranscript
import org.stypox.dicio.skills.carfu.VietnameseNumbers

/**
 * NAV-only transcript cleanup. Strips command prefixes and polite fillers, optionally
 * rewrites high-confidence spoken numbers, and preserves address / place / relation tokens.
 *
 * Does not invent destinations and is isolated from Media grammar.
 */
internal fun interface SpokenNumberConverter {
    fun parseNumberAt(tokens: List<String>, start: Int): Pair<Long, Int>?
}

internal object VietnameseSpokenNumberConverter : SpokenNumberConverter {
    override fun parseNumberAt(tokens: List<String>, start: Int): Pair<Long, Int>? =
        VietnameseNumbers.parseNumberAt(tokens, start)
}

internal data class NormalizedNavigationAddress(
    val rawPartial: String,
    val destination: String,
    val destinationFolded: String,
    val tokenCount: Int,
    val matchedCommand: Boolean,
) {
    val hasDestination: Boolean get() = destination.isNotBlank()
}

internal object NavigationAddressNormalizer {
    /**
     * Longest-first spoken prefixes, including a trailing space so a bare command
     * does not steal the start of an address that happens to share those words.
     */
    internal val COMMAND_PREFIXES: List<String> = listOf(
        "chi duong den ",
        "chi duong toi ",
        "dan duong den ",
        "dan duong toi ",
        "tim duong den ",
        "tim duong toi ",
        "dua toi den ",
        "dua toi toi ",
        "di den ",
        "di toi ",
        "chi duong ve ",
        "dan duong ve ",
        "mo ban do den ",
        "chi duong ",
        "dan duong ",
    )

    private val LEADING_FILLERS = listOf(
        "giup toi ",
        "cho toi ",
        "giup minh ",
    )

    private val TRAILING_FILLERS = setOf("nhe", "voi", "di")

    /**
     * Heads that typically take a house/lane number. Single-token number conversion
     * is only applied after these — not after `đường`/`phố` where `Nam` is a name.
     */
    private val ADDRESS_NUMBER_HEADS = setOf("so", "ngo", "ngach", "hem", "to", "khu")

    fun parse(
        raw: String,
        numberConverter: SpokenNumberConverter = VietnameseSpokenNumberConverter,
    ): NormalizedNavigationAddress {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return NormalizedNavigationAddress(trimmed, "", "", 0, matchedCommand = false)
        }
        val foldedAfterFiller = stripLeadingFillers(VietnameseTranscript.foldForMatch(trimmed))
        val prefix = matchedPrefix(foldedAfterFiller)
            ?: return NormalizedNavigationAddress(trimmed, "", "", 0, matchedCommand = false)
        val destFoldedUnstripped = if (prefix.endsWith(" ")) {
            foldedAfterFiller.removePrefix(prefix).trim()
        } else {
            ""
        }
        val destFolded = stripLeadingFillers(destFoldedUnstripped)
        if (destFolded.isEmpty()) {
            return NormalizedNavigationAddress(trimmed, "", "", 0, matchedCommand = true)
        }
        val destTokenCount = destFolded.split(" ").filter { it.isNotEmpty() }.size
        var displayTokens = VietnameseCommandUnderstanding.extractTrailingWords(trimmed, destTokenCount)
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
        var foldedTokens = destFolded.split(" ").filter { it.isNotEmpty() }
        if (displayTokens.size != foldedTokens.size) {
            foldedTokens = displayTokens.map { VietnameseTranscript.foldForMatch(it) }
        }
        val stripped = stripTrailingFillers(displayTokens, foldedTokens)
        val numbered = rewriteSpokenNumbers(stripped.first, stripped.second, numberConverter)
        val destination = numbered.joinToString(" ").trim()
        return NormalizedNavigationAddress(
            rawPartial = trimmed,
            destination = destination,
            destinationFolded = VietnameseTranscript.foldForMatch(destination),
            tokenCount = destination.split(" ").filter { it.isNotEmpty() }.size,
            matchedCommand = true,
        )
    }

    fun looksLikeNavigationCommand(rawOrFolded: String): Boolean {
        val folded = stripLeadingFillers(VietnameseTranscript.foldForMatch(rawOrFolded))
        return matchedPrefix(folded) != null
    }

    private fun matchedPrefix(folded: String): String? {
        COMMAND_PREFIXES.firstOrNull { folded.startsWith(it) }?.let { return it }
        return COMMAND_PREFIXES.map { it.trimEnd() }.firstOrNull { folded == it }
    }

    private fun stripLeadingFillers(folded: String): String {
        var current = folded.trim()
        var changed = true
        while (changed) {
            changed = false
            for (filler in LEADING_FILLERS) {
                if (current.startsWith(filler)) {
                    current = current.removePrefix(filler).trim()
                    changed = true
                }
            }
        }
        return current
    }

    private fun stripTrailingFillers(
        display: List<String>,
        folded: List<String>,
    ): Pair<List<String>, List<String>> {
        if (display.isEmpty() || display.size != folded.size) {
            return display to folded
        }
        var end = display.size
        while (end > 1 && folded[end - 1] in TRAILING_FILLERS) {
            end--
        }
        return display.take(end) to folded.take(end)
    }

    private fun rewriteSpokenNumbers(
        display: List<String>,
        folded: List<String>,
        numberConverter: SpokenNumberConverter,
    ): List<String> {
        if (display.size != folded.size) return display
        val out = ArrayList<String>(display.size)
        var i = 0
        while (i < folded.size) {
            val parsed = numberConverter.parseNumberAt(folded, i)
            if (parsed != null) {
                val (value, consumed) = parsed
                val prev = if (i > 0) folded[i - 1] else ""
                val afterNumberHead = prev in ADDRESS_NUMBER_HEADS
                val digitToken = folded[i].all { it.isDigit() }
                if (digitToken || consumed >= 2 || afterNumberHead) {
                    out += value.toString()
                    i += consumed
                    continue
                }
            }
            out += display[i]
            i++
        }
        return out
    }
}
