package org.stypox.dicio.resolver.query

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class QueryNormalizerTest : StringSpec({
    "trims and folds Vietnamese case and accents" {
        val a = QueryNormalizer.normalize("Đừng Xa Em Đêm Nay")
        val b = QueryNormalizer.normalize("đừng xa em đêm nay")
        val c = QueryNormalizer.normalize("ĐỪNG XA EM ĐÊM NAY")
        val d = QueryNormalizer.normalize("  Đừng Xa Em Đêm Nay  ")
        a.folded shouldBe "dung xa em dem nay"
        b.folded shouldBe a.folded
        c.folded shouldBe a.folded
        d.folded shouldBe a.folded
        d.original shouldBe "Đừng Xa Em Đêm Nay"
        a.searchQuery shouldBe "Đừng Xa Em Đêm Nay"
    }

    "keeps requested variant tokens" {
        val karaoke = QueryNormalizer.normalize("Đừng Xa Em Đêm Nay karaoke")
        karaoke.variants.karaoke shouldBe true
        karaoke.coreTokens shouldBe listOf("dung", "xa", "em", "dem", "nay")
        karaoke.searchQuery.lowercase().contains("karaoke") shouldBe true

        val remix = QueryNormalizer.normalize("dung xa em dem nay remix")
        remix.variants.remix shouldBe true
        remix.variants.karaoke shouldBe false
    }

    "detects multi-word sped up" {
        QueryNormalizer.normalize("hello sped up").variants.spedUp shouldBe true
        QueryNormalizer.normalize("hello nightcore").variants.nightcore shouldBe true
    }
})
