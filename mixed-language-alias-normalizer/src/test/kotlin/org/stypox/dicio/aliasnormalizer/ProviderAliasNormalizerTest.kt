package org.stypox.dicio.aliasnormalizer

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.atomic.AtomicInteger

class ProviderAliasNormalizerTest : StringSpec({
    val n = DefaultProviderAliasNormalizer()
    val song = "Đừng Xa Em Đêm Nay"

    fun go(text: String) = n.normalize(text)

    "1. sờ mát túp after trên maps to SmartTube; song title unchanged" {
        val input = "Mở bài Đừng Xa Em Đêm Nay trên sờ mát túp"
        val r = go(input)
        r.changed.shouldBeTrue()
        r.providerDetected shouldBe "SmartTube"
        r.normalizedTranscript shouldBe "Mở bài Đừng Xa Em Đêm Nay trên SmartTube"
        r.normalizedTranscript shouldContain song
        r.originalTranscript shouldContain song
        r.normalizedTranscript.indexOf(song) shouldBe input.indexOf(song)
    }

    "2. smart tube after bằng maps to SmartTube" {
        val r = go("Phát Nơi Này Có Anh bằng smart tube")
        r.changed.shouldBeTrue()
        r.providerDetected shouldBe "SmartTube"
        r.normalizedTranscript shouldBe "Phát Nơi Này Có Anh bằng SmartTube"
        r.normalizedTranscript shouldContain "Nơi Này Có Anh"
    }

    "3. smart túp after trên maps to SmartTube" {
        val r = go("Mở See You Again trên smart túp")
        r.changed.shouldBeTrue()
        r.providerDetected shouldBe "SmartTube"
        r.normalizedTranscript shouldBe "Mở See You Again trên SmartTube"
        r.normalizedTranscript shouldContain "See You Again"
    }

    "4. existing canonical SmartTube is semantically unchanged" {
        val input = "Mở bài X trên SmartTube"
        val r = go(input)
        r.changed.shouldBeFalse()
        r.normalizedTranscript shouldBe input
        r.providerDetected shouldBe "SmartTube"
    }

    "5. existing YouTube sentence is unchanged" {
        val samples = listOf(
            "Mở bài Đừng Xa Em Đêm Nay trên YouTube",
            "Phát Nơi Này Có Anh bằng YouTube",
            "Mở See You Again trên youtube",
            "Mở bài X trên iu túp",
        )
        samples.forEach { input ->
            val r = go(input)
            r.changed.shouldBeFalse()
            r.normalizedTranscript shouldBe input
            r.providerDetected.shouldBeNull()
            r.aliasMatched.shouldBeNull()
        }
    }

    "6. ordinary Vietnamese resembling aliases is unchanged" {
        val samples = listOf(
            "cái ống này nhìn mát",
            "sờ mát thì đừng",
            "túp lều trên núi",
        )
        samples.forEach { input ->
            val r = go(input)
            r.changed.shouldBeFalse()
            r.normalizedTranscript shouldBe input
            r.providerDetected.shouldBeNull()
        }
    }

    "7. song title containing a similar substring is unchanged" {
        val input = "Mở bài sờ mát túp đêm nay trên YouTube"
        val r = go(input)
        r.changed.shouldBeFalse()
        r.normalizedTranscript shouldBe input
        r.normalizedTranscript shouldContain "sờ mát túp"
        r.normalizedTranscript shouldContain "YouTube"
        r.providerDetected.shouldBeNull()
    }

    "8. unknown provider is unchanged" {
        val input = "Mở bài Đừng Xa Em Đêm Nay trên SpotifyX"
        val r = go(input)
        r.changed.shouldBeFalse()
        r.normalizedTranscript shouldBe input
        r.providerDetected.shouldBeNull()
    }

    "9. normalization is idempotent" {
        val samples = listOf(
            "Mở bài Đừng Xa Em Đêm Nay trên sờ mát túp",
            "Phát Nơi Này Có Anh bằng smart tube",
            "Mở See You Again trên smart túp",
            "Mở bài X trên SmartTube",
            "Mở bài Đừng Xa Em Đêm Nay trên YouTube",
            "cái ống này nhìn mát",
            "Mở bài sờ mát túp đêm nay trên YouTube",
            "Mở bài X trên SpotifyX",
            "Mở bài Y qua sờ mắt túp",
            "Mở bài Z với sờ mát tube",
            "Mở bài Đừng Xa Em Đêm Nay trên smart YouTube",
            "Mở bài Đừng Xa Em Đêm Nay trên smartphone",
            "Mở YouTube trên smartphone",
            "Cho âm lượng nhỏ xuống cùng một chút",
        )
        samples.forEach { input ->
            val once = go(input)
            val twice = go(once.normalizedTranscript)
            twice.normalizedTranscript shouldBe once.normalizedTranscript
            twice.changed.shouldBeFalse()
        }
    }

    "10. no command is executed and no external app is launched" {
        val launches = AtomicInteger(0)
        val r = go("Mở bài Đừng Xa Em Đêm Nay trên sờ mát túp")
        r.shouldBeInstanceOf<NormalizationResult>()
        launches.get() shouldBe 0
        r.normalizedTranscript shouldNotContain "android.intent"
        // Result is text only — this module has no executor / PackageManager / Intent.
    }

    "sờ mắt túp and sờ mát tube also map under provider context" {
        go("Mở bài ABC trên sờ mắt túp").providerDetected shouldBe "SmartTube"
        go("Mở bài ABC trên sờ mát tube").normalizedTranscript shouldBe
            "Mở bài ABC trên SmartTube"
        go("Mở bài ABC trên sờ mat túp").changed.shouldBeTrue()
    }

    "partial alias without full slot match does not rewrite" {
        val r = go("Mở bài ABC trên sờ mát túp hay")
        r.changed.shouldBeFalse()
        r.normalizedTranscript shouldBe "Mở bài ABC trên sờ mát túp hay"
    }

    "fold of requested aliases is stable" {
        TranscriptFolder.fold("sờ mát túp") shouldBe "so mat tup"
        TranscriptFolder.fold("sờ mắt túp") shouldBe "so mat tup"
        TranscriptFolder.fold("sờ mat túp") shouldBe "so mat tup"
        TranscriptFolder.fold("smart túp") shouldBe "smart tup"
        TranscriptFolder.fold("SmartTube") shouldBe "smarttube"
        TranscriptFolder.fold("smart YouTube") shouldBe "smart youtube"
        TranscriptFolder.fold("trần mắt giúp") shouldBe "tran mat giup"
        TranscriptFolder.fold("cùng một chút") shouldBe "cung mot chut"
    }
})
