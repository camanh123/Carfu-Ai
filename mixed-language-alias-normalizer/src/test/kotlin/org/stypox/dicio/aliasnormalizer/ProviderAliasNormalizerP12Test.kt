package org.stypox.dicio.aliasnormalizer

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain

/**
 * P1.2 — CARFU device-observed SmartTube STT aliases, provider-slot only.
 */
class ProviderAliasNormalizerP12Test : StringSpec({
    val n = DefaultProviderAliasNormalizer()
    val song = "Đừng Xa Em Đêm Nay"
    val media = "Mở bài $song"

    fun go(text: String) = n.normalize(text)

    fun assertSlotRewrite(raw: String, expectedAlias: String) {
        val r = go(raw)
        r.changed.shouldBeTrue()
        r.providerDetected shouldBe "SmartTube"
        r.aliasMatched shouldBe expectedAlias
        r.originalTranscript shouldBe raw
        val songAt = raw.indexOf(song)
        songAt shouldBe r.normalizedTranscript.indexOf(song)
        raw.substring(0, songAt + song.length) shouldBe
            r.normalizedTranscript.substring(0, songAt + song.length)
        val prep = " trên "
        val prepAt = raw.lastIndexOf(prep)
        prepAt shouldBe r.normalizedTranscript.lastIndexOf(prep)
        raw.substring(0, prepAt + prep.length) shouldBe
            r.normalizedTranscript.substring(0, prepAt + prep.length)
        r.normalizedTranscript shouldEndWith "SmartTube"
        r.normalizedTranscript shouldBe "$media trên SmartTube"
    }

    fun assertUnchanged(raw: String) {
        val r = go(raw)
        r.changed.shouldBeFalse()
        r.normalizedTranscript shouldBe raw
        r.providerDetected.shouldBeNull()
        r.aliasMatched.shouldBeNull()
    }

    "P1.2-1. RAW 'smart YouTube' in provider slot → SmartTube" {
        assertSlotRewrite("$media trên smart YouTube", "smart YouTube")
    }

    "P1.2-2. RAW 'smartphone' in provider slot → SmartTube" {
        assertSlotRewrite("$media trên smartphone", "smartphone")
    }

    "P1.2-3. RAW 'trần mắt giúp' in provider slot → SmartTube" {
        assertSlotRewrite("$media trên trần mắt giúp", "trần mắt giúp")
    }

    "P1.2-4. RAW 'cùng một chút' in provider slot → SmartTube" {
        assertSlotRewrite("$media trên cùng một chút", "cùng một chút")
    }

    "P1.2-5. RAW 'trần mắt chút' in provider slot → SmartTube" {
        assertSlotRewrite("$media trên trần mắt chút", "trần mắt chút")
    }

    "P1.2-6. smartphone used as a device is unchanged" {
        assertUnchanged("Tôi đang dùng smartphone")
        assertUnchanged("Tìm giá smartphone")
        assertUnchanged("Pin smartphone yếu")
    }

    "P1.2-7. Mở YouTube trên smartphone does not steal YouTube" {
        assertUnchanged("Mở YouTube trên smartphone")
        assertUnchanged("Mở YouTube trên smart YouTube")
        assertUnchanged("Bật YouTube trên smartphone")
    }

    "P1.2-8. YouTube as the provider slot stays YouTube" {
        listOf(
            "Mở bài Đừng Xa Em Đêm Nay trên YouTube",
            "Phát Nơi Này Có Anh bằng YouTube",
            "Mở See You Again trên youtube",
        ).forEach { input ->
            val r = go(input)
            r.changed.shouldBeFalse()
            r.normalizedTranscript shouldBe input
            r.providerDetected shouldBe "YouTube"
            r.normalizedTranscript shouldNotContain "SmartTube"
        }
    }

    "P1.2-9. smart YouTube inside a song title is unchanged" {
        val input = "Mở bài smart YouTube remix trên YouTube"
        val r = go(input)
        r.changed.shouldBeFalse()
        r.normalizedTranscript shouldBe input
        r.normalizedTranscript shouldNotContain "SmartTube"
        r.providerDetected shouldBe "YouTube"
    }

    "P1.2-10. cùng một chút in ordinary Vietnamese is unchanged" {
        assertUnchanged("Cho âm lượng nhỏ xuống cùng một chút")
        assertUnchanged("Nói nhỏ cùng một chút")
        assertUnchanged("Giảm tốc độ cùng một chút")
    }

    "P1.2-11. standalone ambiguous aliases without media context are unchanged" {
        assertUnchanged("smart YouTube")
        assertUnchanged("smartphone")
        assertUnchanged("trần mắt giúp")
        assertUnchanged("cùng một chút")
        assertUnchanged("trần mắt chút")
        assertUnchanged("trên smartphone")
        assertUnchanged("trên smart YouTube")
    }

    "P1.2-12. unknown provider is unchanged" {
        assertUnchanged("Mở bài Đừng Xa Em Đêm Nay trên SpotifyX")
        assertUnchanged("Mở bài Đừng Xa Em Đêm Nay trên Netflix")
    }

    "P1.2-13. navigation commands are unchanged" {
        assertUnchanged("Dẫn tôi đến sân bay bằng smartphone")
        assertUnchanged("Chỉ đường đến nhà bằng smartphone")
        assertUnchanged("Điều hướng tới công viên trên smartphone")
        assertUnchanged("Mở Google Maps trên smartphone")
        assertUnchanged("Mở Maps trên smartphone")
    }

    "P1.2-14. search / non-media commands are unchanged" {
        assertUnchanged("Tìm nhà hàng trên smartphone")
        assertUnchanged("Tìm giá smartphone")
        assertUnchanged("Tìm đường đến sân bay bằng smartphone")
    }

    "P1.2-15. previous proven aliases still rewrite" {
        go("Mở bài Đừng Xa Em Đêm Nay trên sờ mát túp").providerDetected shouldBe "SmartTube"
        go("Phát Nơi Này Có Anh bằng smart tube").changed.shouldBeTrue()
        go("Mở See You Again trên smart túp").normalizedTranscript shouldBe
            "Mở See You Again trên SmartTube"
    }

    "P1.2-16. P1.2 positives are idempotent" {
        val samples = listOf(
            "$media trên smart YouTube",
            "$media trên smartphone",
            "$media trên trần mắt giúp",
            "$media trên cùng một chút",
            "$media trên trần mắt chút",
            "$media trên YouTube",
            "Tôi đang dùng smartphone",
            "Cho âm lượng nhỏ xuống cùng một chút",
            "Mở YouTube trên smartphone",
            "smart YouTube",
        )
        samples.forEach { input ->
            val once = go(input)
            val twice = go(once.normalizedTranscript)
            twice.normalizedTranscript shouldBe once.normalizedTranscript
            twice.changed.shouldBeFalse()
        }
    }

    "P1.2-17. registry has no bare YouTube alias" {
        val folded = ProviderAliasRegistry().all().map { it.foldedForm }
        folded.shouldNotContain("youtube")
        folded.shouldNotContain("you tube")
        folded.shouldNotContain("yt")
    }

    "P1.2-18. folds of device-observed aliases are exact keys" {
        TranscriptFolder.fold("smart YouTube") shouldBe "smart youtube"
        TranscriptFolder.fold("smartphone") shouldBe "smartphone"
        TranscriptFolder.fold("trần mắt giúp") shouldBe "tran mat giup"
        TranscriptFolder.fold("cùng một chút") shouldBe "cung mot chut"
        TranscriptFolder.fold("trần mắt chút") shouldBe "tran mat chut"
    }

    "P1.2-19. substring / incomplete slot does not rewrite" {
        assertUnchanged("Mở bài Đừng Xa Em Đêm Nay trên smart YouTube remix")
        assertUnchanged("Mở bài Đừng Xa Em Đêm Nay trên smartphone của tôi")
        assertUnchanged("Mở bài Đừng Xa Em Đêm Nay trên cùng một chút nữa")
    }
})
