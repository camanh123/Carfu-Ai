package org.stypox.dicio.skills.carfu.nlu

import org.stypox.dicio.io.session.CanonicalCommand
import org.stypox.dicio.io.session.SemanticCompleteness
import org.stypox.dicio.io.session.UnderstandingResult
import org.stypox.dicio.io.session.VietnameseTranscript

/**
 * NAV-only completeness + end-of-utterance timing. Isolated from Media / OpenApp commit.
 *
 * Semantically valid destination != user has finished speaking.
 */
internal object NavigationCommitPolicy {
    /**
     * Rolling silence/stability window after the last meaningful destination growth.
     * OEM live partials typically arrive every ~200–400ms. 800ms sits in the requested
     * 700–1000ms band: long enough for the next address token, short enough that
     * "Mỹ Đình" still commits without waiting for Android EOS.
     */
    const val NAV_STABILIZATION_MS: Long = 800L

    private val INCOMPLETE_HEADS_FOLDED = setOf(
        "ngo", "ngach", "hem", "so", "duong", "pho", "phuong", "quan", "huyen",
        "xa", "thon", "to", "khu", "toa", "benh", "vien", "truong", "cho",
        "san", "bay", "ben", "xe", "tau",
    )

    private val INCOMPLETE_EXACT_FOLDED = setOf(
        "ngo", "ngach", "hem", "so", "duong", "pho", "phuong", "quan", "huyen", "xa",
        "thon", "to", "khu", "toa", "truong", "cho",
        "benh vien", "cay xang", "chung cu", "nha hang", "so nha", "thi tran", "thanh pho",
        "san bay", "ben xe", "ben tau", "toa nha",
    )

    private val RELATIONAL_FOLDED = setOf("o", "gan", "canh", "trong")

    enum class Completeness {
        EMPTY,
        INCOMPLETE_HEAD,
        COMPLETE,
    }

    fun completenessOf(destination: String): Completeness {
        val folded = VietnameseTranscript.foldForMatch(destination)
        if (folded.isBlank()) return Completeness.EMPTY
        if (isIncompleteDestination(folded, alreadyFolded = true)) {
            return Completeness.INCOMPLETE_HEAD
        }
        return Completeness.COMPLETE
    }

    fun isIncompleteDestination(destination: String, alreadyFolded: Boolean = false): Boolean {
        val folded = if (alreadyFolded) destination.trim() else VietnameseTranscript.foldForMatch(destination)
        if (folded.isBlank()) return true
        val tokens = folded.split(" ").filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return true
        val core = stripTrailingRelationals(tokens)
        if (core.isEmpty()) return true
        val coreJoined = core.joinToString(" ")
        if (coreJoined in INCOMPLETE_EXACT_FOLDED) return true
        // Address heads are valid mid-phrase but incomplete when they are the final token
        // ("số 25 phố" waits for the street name; "Chợ Hôm" is complete because last ≠ chợ).
        if (core.last() in INCOMPLETE_HEADS_FOLDED) return true
        if (core.all { it in INCOMPLETE_HEADS_FOLDED }) return true
        return false
    }

    fun isIncompleteDestination(parse: UnderstandingResult): Boolean {
        val dest = (parse.command as? CanonicalCommand.Navigate)?.destination
            ?: parse.destination.orEmpty()
        return isIncompleteDestination(dest)
    }

    fun isCompleteNavigate(parse: UnderstandingResult): Boolean {
        if (parse.command !is CanonicalCommand.Navigate) return false
        if (parse.completeness != SemanticCompleteness.COMPLETE) return false
        return !isIncompleteDestination(parse)
    }

    private fun stripTrailingRelationals(tokens: List<String>): List<String> {
        var t = tokens
        while (t.isNotEmpty()) {
            if (t.size >= 2 && t[t.lastIndex - 1] == "doi" && t.last() == "dien") {
                t = t.dropLast(2)
                continue
            }
            if (t.last() in RELATIONAL_FOLDED) {
                t = t.dropLast(1)
                continue
            }
            break
        }
        return t
    }
}
