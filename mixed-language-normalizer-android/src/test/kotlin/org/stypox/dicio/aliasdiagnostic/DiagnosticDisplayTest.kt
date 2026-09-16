package org.stypox.dicio.aliasdiagnostic

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.stypox.dicio.aliasnormalizer.DefaultProviderAliasNormalizer
import org.stypox.dicio.aliasnormalizer.ProviderAliasNormalizer

class DiagnosticDisplayTest : StringSpec({
    val normalizer: ProviderAliasNormalizer = DiagnosticDisplay.defaultNormalizer()

    "defaultNormalizer reuses the existing JVM DefaultProviderAliasNormalizer" {
        DiagnosticDisplay.defaultNormalizer().shouldBeInstanceOf<DefaultProviderAliasNormalizer>()
    }

    "1. final STT alias after trên shows RAW vs NORMALIZED SmartTube" {
        val raw = "Mở bài Đừng Xa Em Đêm Nay trên sờ mát túp"
        val snap = DiagnosticDisplay.of(normalizer, raw, status = "final")
        snap.rawStt shouldBe raw
        snap.normalized shouldBe "Mở bài Đừng Xa Em Đêm Nay trên SmartTube"
        snap.provider shouldBe "SmartTube"
        snap.aliasMatched shouldBe "sờ mát túp"
        snap.changed.shouldBeTrue()
        snap.format() shouldContain "RAW STT: $raw"
        snap.format() shouldContain "CHANGED: true"
        snap.format() shouldContain "PRODUCTION_WIRED: NO"
        snap.format() shouldContain "LOCALE: vi-VN"
    }

    "2. canonical SmartTube is displayed unchanged" {
        val raw = "Phát Nơi Này Có Anh trên SmartTube"
        val snap = DiagnosticDisplay.of(normalizer, raw, status = "final")
        snap.normalized shouldBe raw
        snap.provider shouldBe "SmartTube"
        snap.changed.shouldBeFalse()
        snap.format() shouldContain "CHANGED: false"
    }

    "3. YouTube transcript is displayed unchanged; no speculative alias" {
        val raw = "Mở See You Again trên YouTube"
        val snap = DiagnosticDisplay.of(normalizer, raw, status = "final")
        snap.normalized shouldBe raw
        snap.provider shouldBe ""
        snap.aliasMatched shouldBe ""
        snap.changed.shouldBeFalse()
    }

    "4. live partial uses the same normalizer" {
        val partial = "Mở bài X trên smart tube"
        val snap = DiagnosticDisplay.of(
            normalizer,
            rawTranscript = partial,
            partialTranscript = partial,
            status = "partial",
        )
        snap.status shouldBe "partial"
        snap.partialTranscript shouldBe partial
        snap.normalized shouldBe "Mở bài X trên SmartTube"
        snap.changed.shouldBeTrue()
    }

    "display does not invent aliases for unknown STT forms" {
        val raw = "Mở bài Đừng Xa Em Đêm Nay trên sờ mát túp hay"
        val snap = DiagnosticDisplay.of(normalizer, raw, status = "final")
        snap.changed.shouldBeFalse()
        snap.normalized shouldBe raw
        snap.provider shouldBe ""
    }
})
