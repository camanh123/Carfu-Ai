package org.stypox.dicio.aliasdiagnostic

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.stypox.dicio.aliasnormalizer.DefaultProviderAliasNormalizer

class DiagnosticDisplayTest : StringSpec({
    val normalizer = DiagnosticDisplay.defaultNormalizer()

    "defaultNormalizer reuses the frozen Phase 2A DefaultProviderAliasNormalizer" {
        DiagnosticDisplay.defaultNormalizer().shouldBeInstanceOf<DefaultProviderAliasNormalizer>()
    }

    "RAW is preserved byte-for-byte and NORMALIZED is engine output" {
        val raw = "Mở sea games trên smart stood"
        val snap = DiagnosticDisplay.of(normalizer, raw, status = "final")
        snap.rawStt shouldBe raw
        snap.normalized shouldBe "Mở sea games trên SmartTube"
        snap.canonicalProvider shouldBe "SmartTube"
        snap.shouldRewrite shouldBe "YES"
        snap.exactMatch shouldBe "NO"
        snap.aliasMatch shouldBe "NO"
        snap.format() shouldContain "RAW STT: $raw"
        snap.format() shouldContain "NORMALIZED: Mở sea games trên SmartTube"
        snap.format() shouldContain "CANONICAL PROVIDER: SmartTube"
        snap.format() shouldContain "PRODUCTION_WIRED: NO"
    }

    "proven alias still displays through the same engine" {
        val raw = "Mở bài Đừng Xa Em Đêm Nay trên sờ mát túp"
        val snap = DiagnosticDisplay.of(normalizer, raw, status = "final")
        snap.rawStt shouldBe raw
        snap.normalized shouldBe "Mở bài Đừng Xa Em Đêm Nay trên SmartTube"
        snap.aliasMatch shouldBe "YES"
        snap.shouldRewrite shouldBe "YES"
        snap.changed.shouldBeTrue()
    }

    "YouTube control is shown as YouTube, not rewritten to SmartTube" {
        val raw = "Mở See You Again trên YouTube"
        val snap = DiagnosticDisplay.of(normalizer, raw, status = "final")
        snap.rawStt shouldBe raw
        snap.normalized shouldBe raw
        snap.canonicalProvider shouldBe "YouTube"
        snap.shouldRewrite shouldBe "NO"
        snap.format() shouldNotContain "CANONICAL PROVIDER: SmartTube"
    }

    "UNKNOWN results are visible, including spotify" {
        val raw = "Mở bài Đừng Xa Em Đêm Nay trên spotify"
        val snap = DiagnosticDisplay.of(normalizer, raw, status = "final")
        snap.rawStt shouldBe raw
        snap.normalized shouldBe raw
        snap.canonicalProvider shouldBe DiagnosticDisplay.UNKNOWN
        snap.shouldRewrite shouldBe "NO"
        snap.confidence shouldBe "LOW"
        snap.format() shouldContain "CANONICAL PROVIDER: UNKNOWN"
        snap.format() shouldContain "SHOULD REWRITE: NO"
    }

    "ordinary smartphone is UNKNOWN / no rewrite" {
        val raw = "Tôi đang dùng smartphone"
        val snap = DiagnosticDisplay.of(normalizer, raw, status = "final")
        snap.rawStt shouldBe raw
        snap.normalized shouldBe raw
        snap.canonicalProvider shouldBe DiagnosticDisplay.UNKNOWN
        snap.shouldRewrite shouldBe "NO"
        snap.mediaContext shouldBe "NO"
        snap.changed.shouldBeFalse()
    }

    "COPY dump includes resolution fields and history newest first" {
        val snap = DiagnosticDisplay.of(
            normalizer,
            "Mở bài X trên SmartTube",
            status = "final",
        )
        val older = DiagnosticHistoryEntry(
            sessionNumber = 1,
            raw = "older",
            normalized = "older",
            candidate = "",
            canonicalProvider = "UNKNOWN",
            lexicalScore = "0.000",
            phoneticScore = "0.000",
            confidence = "LOW",
            matchType = "NONE",
            rewrite = "NO",
        )
        val newer = snap.toHistoryEntry(2)
        val dump = DiagnosticDisplay.formatDump(snap, listOf(newer, older))
        dump shouldContain "RAW STT:"
        dump shouldContain "LEXICAL SCORE:"
        dump shouldContain "PHONETIC SCORE:"
        dump shouldContain "CONFIDENCE:"
        (dump.indexOf("#2 RAW=") < dump.indexOf("#1 RAW=")).shouldBeTrue()
    }
})
