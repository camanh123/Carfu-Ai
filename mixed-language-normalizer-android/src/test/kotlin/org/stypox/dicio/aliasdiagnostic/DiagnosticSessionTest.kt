package org.stypox.dicio.aliasdiagnostic

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

class DiagnosticSessionTest : StringSpec({
    fun snap(raw: String) = DiagnosticDisplay.of(
        DiagnosticDisplay.defaultNormalizer(),
        raw,
        status = "final",
    )

    "START begins a clean session and does not concatenate prior RAW" {
        var s = DiagnosticSessionState()
        s = DiagnosticSession.begin(s)
        val firstRaw = "Mở bài Đừng Xa Em Đêm Nay trên smart YouTube"
        s = DiagnosticSession.onPartial(s, s.sessionId, "Mở bài")
        s = DiagnosticSession.onFinal(s, s.sessionId, firstRaw, snap(firstRaw))
        s.rawStt shouldBe firstRaw
        s.listening.shouldBeFalse()
        s.history.first().raw shouldBe firstRaw

        s = DiagnosticSession.begin(s)
        s.rawStt shouldBe ""
        s.partial shouldBe ""
        s.currentSnapshot.shouldBeNull()
        s.listening.shouldBeTrue()
        s.history.shouldHaveSize(1)

        val second = "Mở bài X trên smartphone"
        s = DiagnosticSession.onFinal(s, s.sessionId, second, snap(second))
        s.rawStt shouldBe second
        s.rawStt.shouldNotContain("smart YouTube")
        s.rawStt.shouldNotContain("Đừng Xa Em Đêm Nay")
        s.history.first().raw shouldBe second
        s.history[1].raw shouldBe firstRaw
    }

    "stale callbacks from a previous session are ignored" {
        var s = DiagnosticSession.begin(DiagnosticSessionState())
        val old = s.sessionId
        s = DiagnosticSession.begin(s)
        s = DiagnosticSession.onPartial(s, old, "stale partial from session 1")
        s.partial shouldBe ""
        s.rawStt shouldBe ""
        s = DiagnosticSession.onFinal(s, old, "stale final from session 1", snap("stale final from session 1"))
        s.rawStt shouldBe ""
        s.history.none { it.raw.contains("stale") }.shouldBeTrue()
    }

    "history is newest first and capped at 20" {
        var s = DiagnosticSessionState()
        repeat(22) { i ->
            s = DiagnosticSession.begin(s)
            val raw = "Mở bài X trên SmartTube #$i"
            s = DiagnosticSession.onFinal(s, s.sessionId, raw, snap("Mở bài X trên SmartTube"))
        }
        s.history.shouldHaveSize(DiagnosticSession.HISTORY_LIMIT)
        s.history.first().sessionNumber shouldBe 22
        s.history.last().sessionNumber shouldBe 3
    }
})
