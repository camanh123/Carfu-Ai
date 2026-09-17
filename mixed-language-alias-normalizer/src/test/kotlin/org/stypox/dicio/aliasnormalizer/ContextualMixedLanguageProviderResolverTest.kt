package org.stypox.dicio.aliasnormalizer

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf

class ContextualMixedLanguageProviderResolverTest : StringSpec({
    val resolver = ContextualMixedLanguageProviderResolver()
    val normalizer = DefaultProviderAliasNormalizer(resolver)
    val song = "Đừng Xa Em Đêm Nay"
    val media = "Mở bài $song"

    fun go(text: String) = normalizer.normalize(text)
    fun res(text: String) = resolver.resolve(text)

    val unseenSmartTube = listOf(
        "smart stood",
        "smart toob",
        "smart tude",
        "smaart tube",
    )

    "A. exact canonical SmartTube / YouTube / MusicLoop" {
        res("$media trên SmartTube").canonicalProvider shouldBe "SmartTube"
        res("$media trên SmartTube").shouldRewrite.shouldBeFalse()
        res("$media trên YouTube").canonicalProvider shouldBe "YouTube"
        res("$media trên YouTube").shouldRewrite.shouldBeFalse()
        val music = res("Mở bài X trên MusicLoop")
        music.canonicalProvider shouldBe "MusicLoop"
        music.shouldRewrite.shouldBeFalse()
        music.exactMatch.shouldBeTrue()
    }

    "B. existing proven SmartTube aliases still rewrite" {
        go("$media trên sờ mát túp").changed.shouldBeTrue()
        go("$media trên smart YouTube").providerDetected shouldBe "SmartTube"
        go("$media trên smartphone").normalizedTranscript shouldBe "$media trên SmartTube"
        go("Phát Nơi Này Có Anh bằng smart tube").changed.shouldBeTrue()
        res("$media trên smart YouTube").exactAliasMatch.shouldBeTrue()
    }

    "C. unseen phonetic variants are not aliases and still HIGH-rewrite" {
        val aliasFolds = ProviderAliasRegistry().all().map { it.foldedForm }
        unseenSmartTube.forEach { variant ->
            aliasFolds.shouldNotContain(TranscriptFolder.fold(variant))
            val input = "$media trên $variant"
            val r = res(input)
            r.exactAliasMatch.shouldBeFalse()
            r.exactMatch.shouldBeFalse()
            r.confidence shouldBe Confidence.HIGH
            r.canonicalProvider shouldBe "SmartTube"
            r.shouldRewrite.shouldBeTrue()
            r.matchType shouldBe MatchType.PHONETIC
            val n = go(input)
            n.changed.shouldBeTrue()
            n.normalizedTranscript shouldBe "$media trên SmartTube"
            n.normalizedTranscript shouldContain song
            n.originalTranscript.indexOf(song) shouldBe input.indexOf(song)
        }
    }

    "D. YouTube control: never stolen by SmartTube" {
        listOf(
            "$media trên YouTube",
            "$media trên youtube",
            "Phát Nơi Này Có Anh bằng YouTube",
            "Mở See You Again trên you tube",
        ).forEach { input ->
            val r = res(input)
            r.canonicalProvider shouldBe "YouTube"
            go(input).normalizedTranscript.shouldNotContain("SmartTube")
        }
    }

    "E. spotify is UNKNOWN and never becomes SmartTube" {
        val input = "$media trên spotify"
        val r = res(input)
        r.shouldRewrite.shouldBeFalse()
        r.canonicalProvider.shouldBeNull()
        r.confidence shouldBe Confidence.LOW
        go(input).normalizedTranscript shouldBe input
        go(input).providerDetected.shouldBeNull()
    }

    "F. ordinary-language false positives do not rewrite" {
        listOf(
            "Tôi đang dùng smartphone",
            "Tìm giá smartphone",
            "Điện thoại smartphone của tôi",
            "Cho âm lượng nhỏ xuống cùng một chút",
            "Mở YouTube trên smartphone",
            "Mở bài smart tube remix trên YouTube",
            "Tìm đường đến cửa hàng smartphone",
            "Chỉ đường đến Smart City",
            "hôm nay trời đẹp",
            "Dẫn tôi đến sân bay bằng smartphone",
            "Tìm nhà hàng trên smartphone",
        ).forEach { input ->
            go(input).changed.shouldBeFalse()
            go(input).normalizedTranscript shouldBe input
            res(input).shouldRewrite.shouldBeFalse()
        }
        go("Mở bài smart tube remix trên YouTube").normalizedTranscript.shouldNotContain("SmartTube")
    }

    "G. song title / query bytes are never repaired" {
        val input = "Mở sea games trên smart stood"
        val r = go(input)
        r.changed.shouldBeTrue()
        r.normalizedTranscript shouldBe "Mở sea games trên SmartTube"
        r.normalizedTranscript shouldContain "sea games"
        r.normalizedTranscript.shouldNotContain("See You Again")
        val prefix = input.substring(0, input.lastIndexOf(" trên ") + " trên ".length)
        r.normalizedTranscript.startsWith(prefix).shouldBeTrue()
    }

    "H. missing provider slot fails closed" {
        val r = res("Mở bài Đừng Xa Em Đêm Nay")
        r.providerSlotDetected.shouldBeFalse()
        r.shouldRewrite.shouldBeFalse()
        r.confidence shouldBe Confidence.LOW
    }

    "I. missing media context fails closed" {
        listOf(
            "trên smart stood",
            "smartphone",
            "smart YouTube",
            "Tôi đang dùng smartphone",
        ).forEach { input ->
            val r = res(input)
            r.mediaCommand.shouldBeFalse()
            r.shouldRewrite.shouldBeFalse()
        }
    }

    "J. short / ambiguous candidates do not prefer SmartTube" {
        val r = res("Mở bài X trên tube")
        r.shouldRewrite.shouldBeFalse()
        go("Mở bài X trên tube").changed.shouldBeFalse()
        go("Mở bài X trên tube").normalizedTranscript.shouldNotContain("SmartTube")
    }

    "K. idempotence: normalize(normalize(x)) == normalize(x)" {
        val samples = listOf(
            "$media trên smart stood",
            "$media trên SmartTube",
            "$media trên YouTube",
            "$media trên spotify",
            "Mở sea games trên smart toob",
            "Tôi đang dùng smartphone",
            "$media trên sờ mát túp",
        )
        samples.forEach { input ->
            val once = go(input)
            val twice = go(once.normalizedTranscript)
            twice.normalizedTranscript shouldBe once.normalizedTranscript
            twice.changed.shouldBeFalse()
        }
    }

    "L. unicode / case / spacing robustness" {
        go("mở bài x trên SMARTTUBE").providerDetected shouldBe "SmartTube"
        go("Mở bài X trên  smart   stood").normalizedTranscript shouldBe "Mở bài X trên SmartTube"
        go("Mở bài X trên SmartTube.").let { r ->
            // trailing punct is folded for matching; rewrite still HIGH if slot is SmartTube.
            r.providerDetected shouldBe "SmartTube"
        }
    }

    "M. deterministic repeatability" {
        val input = "$media trên smart stood"
        val first = res(input)
        repeat(20) {
            val again = res(input)
            again.canonicalProvider shouldBe first.canonicalProvider
            again.shouldRewrite shouldBe first.shouldRewrite
            again.phoneticScore shouldBe first.phoneticScore
            again.lexicalScore shouldBe first.lexicalScore
            again.finalScore shouldBe first.finalScore
            again.reason shouldBe first.reason
        }
    }

    "explainability fields are populated on a HIGH phonetic hit" {
        val r = res("$media trên smart stood")
        r.originalCandidate shouldBe "smart stood"
        r.evaluatedEntities.map { it.canonicalProvider }.toSet() shouldBe
            setOf("SmartTube", "YouTube", "MusicLoop")
        r.phoneticScore.shouldBeGreaterThanOrEqual(ProviderConfidencePolicy.PHONETIC_HIGH_MIN)
        r.reason shouldBe "high_confidence_rewrite"
        r.ambiguous.shouldBeFalse()
        r.shouldBeInstanceOf<ProviderResolution>()
    }

    "MusicLoop exact is not rewritten to SmartTube" {
        val input = "Mở bài X trên music loop"
        val r = res(input)
        r.canonicalProvider shouldBe "MusicLoop"
        go(input).normalizedTranscript.shouldNotContain("SmartTube")
    }

    "unseen variants are absent from the alias registry" {
        val folds = ProviderAliasRegistry().all().map { it.foldedForm }.toSet()
        unseenSmartTube.map { TranscriptFolder.fold(it) }.forEach { folded ->
            folds.contains(folded).shouldBeFalse()
        }
        folds.contains("spotify").shouldBeFalse()
        folds.contains("smart stood").shouldBeFalse()
    }
})
