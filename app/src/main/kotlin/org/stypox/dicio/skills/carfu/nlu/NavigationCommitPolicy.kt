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

    /**
     * NAV-only extra wait after [NAV_STABILIZATION_MS] when speech may still be
     * active (no EOS, no Android Final). Device-proven: Google may pause partial
     * delivery mid-utterance for ~800ms ("hồ Linh" then "Đàm"). This is not a
     * global STT change and does not apply to OpenApp / PlayMedia.
     */
    const val NAV_CONTINUATION_GRACE_MS: Long = 700L

    fun remainingUntilCommit(stableForMs: Long, speechMayStillBeActive: Boolean): Long {
        val stabilizeLeft = (NAV_STABILIZATION_MS - stableForMs).coerceAtLeast(0L)
        if (stabilizeLeft > 0L) return stabilizeLeft
        if (speechMayStillBeActive) {
            return (NAV_STABILIZATION_MS + NAV_CONTINUATION_GRACE_MS - stableForMs)
                .coerceAtLeast(0L)
        }
        return 0L
    }

    fun commitBlockReason(stableForMs: Long, speechMayStillBeActive: Boolean): String {
        val remaining = remainingUntilCommit(stableForMs, speechMayStillBeActive)
        if (remaining <= 0L) return ""
        return if (stableForMs < NAV_STABILIZATION_MS) {
            "waiting_stable"
        } else {
            "speech_may_still_be_active"
        }
    }

    fun noEosCommitAt(changedAtMs: Long): Long =
        changedAtMs + NAV_STABILIZATION_MS + NAV_CONTINUATION_GRACE_MS

    /**
     * Last-token incompleteness applies only to structural address particles that
     * almost always need a following number/name ("số 25 phố" waits for the street).
     *
     * Place-type words that also occur as the *last token of a proper name* must not
     * use this rule. Device-proven: "Hồ Văn Quán" was marked incomplete because
     * folded last token `quan` lived in the old last-head set, so the tracker kept
     * the shorter "Hồ Văn". Bare "quận" / "chợ" / "trường" remain incomplete via
     * [INCOMPLETE_EXACT_FOLDED].
     */
    private val STRUCTURAL_TRAILING_HEADS = setOf(
        "ngo", "ngach", "hem", "so", "duong", "pho", "phuong",
        "xa", "thon", "to", "khu", "toa",
    )

    /** Compound category heads such as "bệnh viện" / "sân bay" (every token is a head). */
    private val CATEGORY_HEAD_TOKENS = setOf(
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
        // Structural particles only: "số 25 phố" waits. "Hồ Văn Quán" / "Chợ Hôm" do not.
        if (core.last() in STRUCTURAL_TRAILING_HEADS) return true
        if (core.all { it in CATEGORY_HEAD_TOKENS }) return true
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
