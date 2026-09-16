package org.stypox.dicio.aliasdiagnostic

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain

class DiagnosticSessionTest : StringSpec({
    "START begins a clean session and does not concatenate prior RAW" {
        var s = DiagnosticSessionState()
        s = DiagnosticSession.begin(s)
        s = DiagnosticSession.onPartial(s, s.sessionId, "Mở bài")
        s = DiagnosticSession.onFinal(
            s,
            s.sessionId,
            "Mở bài Đừng Xa Em Đêm Nay trên smart YouTube",
        )
        s.rawStt shouldBe "Mở bài Đừng Xa Em Đêm Nay trên smart YouTube"
        s.listening.shouldBeFalse()

        s = DiagnosticSession.begin(s)
        s.rawStt shouldBe ""
        s.partial shouldBe ""
        s.listening.shouldBeTrue()
        s.history.shouldContain("Mở bài Đừng Xa Em Đêm Nay trên smart YouTube")

        s = DiagnosticSession.onFinal(s, s.sessionId, "Mở bài X trên smartphone")
        s.rawStt shouldBe "Mở bài X trên smartphone"
        s.rawStt.shouldNotContain("smart YouTube")
        s.rawStt.shouldNotContain("Đừng Xa Em Đêm Nay")
    }

    "stale callbacks from a previous session are ignored" {
        var s = DiagnosticSession.begin(DiagnosticSessionState())
        val old = s.sessionId
        s = DiagnosticSession.begin(s)
        s = DiagnosticSession.onPartial(s, old, "stale partial from session 1")
        s.partial shouldBe ""
        s.rawStt shouldBe ""
        s = DiagnosticSession.onFinal(s, old, "stale final from session 1")
        s.rawStt shouldBe ""
        s.history.shouldNotContain("stale final from session 1")
    }

    "partials replace live text instead of appending across callbacks" {
        var s = DiagnosticSession.begin(DiagnosticSessionState())
        s = DiagnosticSession.onPartial(s, s.sessionId, "Mở")
        s = DiagnosticSession.onPartial(s, s.sessionId, "Mở bài Đừng Xa")
        s.rawStt shouldBe "Mở bài Đừng Xa"
        s.partial shouldBe "Mở bài Đừng Xa"
        s.rawStt.shouldNotContain("MởMở")
    }
})
