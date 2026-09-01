package org.stypox.dicio.io.session

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class VietnameseTranscriptTest : StringSpec({
    "folds Vietnamese with and without diacritics to the same form" {
        VietnameseTranscript.foldForMatch("Mở YouTube") shouldBe "mo youtube"
        VietnameseTranscript.foldForMatch("mo youtube") shouldBe "mo youtube"
        VietnameseTranscript.foldForMatch("Chỉ đường đến sân bay") shouldBe "chi duong den san bay"
        VietnameseTranscript.foldForMatch("chi duong den san bay") shouldBe "chi duong den san bay"
        VietnameseTranscript.foldForMatch("Mấy giờ rồi?") shouldBe "may gio roi"
        VietnameseTranscript.foldForMatch("may gio roi") shouldBe "may gio roi"
    }

    "trims punctuation and collapses whitespace" {
        val parsed = VietnameseTranscript.parse("  Tăng   âm lượng!!!  ")
        parsed.display shouldBe "Tăng âm lượng!!!"
        parsed.folded shouldBe "tang am luong"
    }

    "rejects empty, echo, and noise-only fragments" {
        VietnameseTranscript.isTooWeakToSubmit("").shouldBeTrue()
        VietnameseTranscript.isTooWeakToSubmit("   ").shouldBeTrue()
        VietnameseTranscript.isTooWeakToSubmit("thổ").shouldBeTrue()
        VietnameseTranscript.isTooWeakToSubmit("hà hồ").shouldBeTrue()
        VietnameseTranscript.isTooWeakToSubmit("người").shouldBeTrue()
        VietnameseTranscript.isTooWeakToSubmit("Tôi nghe đây").shouldBeTrue()
        VietnameseTranscript.isTooWeakToSubmit("toi nghe day").shouldBeTrue()
        VietnameseTranscript.isTooWeakToSubmit("Mở YouTube").shouldBeFalse()
        VietnameseTranscript.isTooWeakToSubmit("Phát nhạc").shouldBeFalse()
    }

    "valid non-empty unmatched speech is not treated as too weak" {
        VietnameseTranscript.isTooWeakToSubmit("một câu không phải lệnh").shouldBeFalse()
        VietnameseTranscript.isTooWeakToSubmit("chỉ đường đến Hồ Gươm").shouldBeFalse()
    }
})

class CarfuCommandRouterTest : StringSpec({
    "routes the 12 CARFU commands with diacritics" {
        CarfuCommandRouter.match("Mở YouTube")!!.intent shouldBe CarfuIntent.OPEN_YOUTUBE
        CarfuCommandRouter.match("Mở bản đồ")!!.intent shouldBe CarfuIntent.OPEN_MAPS
        CarfuCommandRouter.match("Mở MusicLoop")!!.intent shouldBe CarfuIntent.OPEN_MUSICLOOP
        CarfuCommandRouter.match("Mở máy nghe nhạc")!!.intent shouldBe CarfuIntent.OPEN_MUSICLOOP
        CarfuCommandRouter.match("Chỉ đường đến sân bay")!!.intent shouldBe CarfuIntent.NAVIGATE_AIRPORT
        CarfuCommandRouter.match("Chỉ đường về nhà")!!.intent shouldBe CarfuIntent.NAVIGATE_HOME
        CarfuCommandRouter.match("Bài tiếp theo")!!.intent shouldBe CarfuIntent.MEDIA_NEXT
        CarfuCommandRouter.match("Bài trước")!!.intent shouldBe CarfuIntent.MEDIA_PREVIOUS
        CarfuCommandRouter.match("Tạm dừng nhạc")!!.intent shouldBe CarfuIntent.MEDIA_PAUSE
        CarfuCommandRouter.match("Phát nhạc")!!.intent shouldBe CarfuIntent.MEDIA_PLAY
        CarfuCommandRouter.match("Tăng âm lượng")!!.intent shouldBe CarfuIntent.VOLUME_UP
        CarfuCommandRouter.match("Giảm âm lượng")!!.intent shouldBe CarfuIntent.VOLUME_DOWN
        CarfuCommandRouter.match("Mấy giờ rồi?")!!.intent shouldBe CarfuIntent.CURRENT_TIME
    }

    "routes the same commands without diacritics" {
        CarfuCommandRouter.match("mo youtube")!!.intent shouldBe CarfuIntent.OPEN_YOUTUBE
        CarfuCommandRouter.match("mo ban do")!!.intent shouldBe CarfuIntent.OPEN_MAPS
        CarfuCommandRouter.match("chi duong ve nha")!!.intent shouldBe CarfuIntent.NAVIGATE_HOME
        CarfuCommandRouter.match("bai tiep theo")!!.intent shouldBe CarfuIntent.MEDIA_NEXT
        CarfuCommandRouter.match("may gio roi")!!.intent shouldBe CarfuIntent.CURRENT_TIME
    }

    "extracts arbitrary navigation destinations generically" {
        CarfuCommandRouter.match("chỉ đường đến Mỹ Đình")!!.let {
            it.intent shouldBe CarfuIntent.NAVIGATE_PLACE
            it.place shouldBe "Mỹ Đình"
        }
        CarfuCommandRouter.match("chỉ đường đến Hồ Gươm")!!.place shouldBe "Hồ Gươm"
        CarfuCommandRouter.match("chỉ đường đến Bệnh viện Bạch Mai")!!.place shouldBe "Bệnh viện Bạch Mai"
        CarfuCommandRouter.match("dẫn đường đến sân bay Nội Bài")!!.place shouldBe "sân bay Nội Bài"
        CarfuCommandRouter.match("đi đến 120 Trần Duy Hưng")!!.place shouldBe "120 Trần Duy Hưng"
        CarfuCommandRouter.match("mở bản đồ đến Vincom Mega Mall")!!.place shouldBe "Vincom Mega Mall"
        CarfuCommandRouter.match("chỉ đường đến sân vận động mỹ đình")!!.place shouldBe
            "sân vận động mỹ đình"
    }

    "does not hard-code navigation destinations beyond exact airport/home phrases" {
        CarfuCommandRouter.match("chỉ đường đến Mỹ Đình")!!.place shouldBe "Mỹ Đình"
        CarfuCommandRouter.match("chỉ đường đến Hồ Tây")!!.place shouldBe "Hồ Tây"
        CarfuCommandRouter.match("chỉ đường đến cho bến thành")!!.place shouldBe "cho bến thành"
    }

    "does not map corrupted fragments to intents" {
        CarfuCommandRouter.match("thổ").shouldBeNull()
        CarfuCommandRouter.match("hà hồ").shouldBeNull()
        CarfuCommandRouter.match("người").shouldBeNull()
        CarfuCommandRouter.match("Tôi nghe đây").shouldBeNull()
        CarfuCommandRouter.match("").shouldBeNull()
    }

    "explicit Vietnamese search still routes; unrecognized speech is not search" {
        CarfuCommandRouter.match("tìm kiếm youtube")!!.intent shouldBe CarfuIntent.SEARCH
        CarfuCommandRouter.match("tim kiem google")!!.intent shouldBe CarfuIntent.SEARCH
        CarfuCommandRouter.match("mắng đen").shouldBeNull()
        CarfuCommandRouter.match("một câu không phải lệnh").shouldBeNull()
    }

    "matchBest prefers a secondary candidate that maps to a known command" {
        val best = CarfuCommandRouter.matchBest(
            listOf(
                "mở miu sích lúp" to 0.91f,
                "mở music loop" to 0.40f,
            ),
        )
        best!!.candidateIndex shouldBe 1
        best.command.intent shouldBe CarfuIntent.OPEN_MUSICLOOP
        best.transcript shouldBe "mở music loop"
    }

    "matchBest keeps the primary candidate when only it matches" {
        val best = CarfuCommandRouter.matchBest(
            listOf(
                "mấy giờ rồi" to 0.88f,
                "mai gio roi" to 0.55f,
            ),
        )
        best!!.candidateIndex shouldBe 0
        best.command.intent shouldBe CarfuIntent.CURRENT_TIME
    }
})

class CarfuCommandRouterDestinationTest : StringSpec({
    "extractTrailingWords preserves diacritics from the raw transcript tail" {
        CarfuCommandRouter.extractTrailingWords("chỉ đường đến sân vận động mỹ đình", 5) shouldBe
            "sân vận động mỹ đình"
        CarfuCommandRouter.extractTrailingWords("đi đến 120 Trần Duy Hưng", 4) shouldBe
            "120 Trần Duy Hưng"
    }
})

class CarfuBaselineCommandRouterTest : StringSpec({
    "required CARFU baseline commands route through the primary final transcript path" {
        CarfuCommandRouter.match("Mấy giờ rồi")!!.intent shouldBe CarfuIntent.CURRENT_TIME
        CarfuCommandRouter.match("Mở SmartTube")!!.intent shouldBe CarfuIntent.OPEN_SMARTTUBE
        CarfuCommandRouter.match("Mở MusicLoop")!!.intent shouldBe CarfuIntent.OPEN_MUSICLOOP
        CarfuCommandRouter.match("Mở máy nghe nhạc")!!.intent shouldBe CarfuIntent.OPEN_MUSICLOOP
        CarfuCommandRouter.match("Mở Zalo")!!.intent shouldBe CarfuIntent.OPEN_ZALO
        CarfuCommandRouter.match("Tăng âm lượng")!!.intent shouldBe CarfuIntent.VOLUME_UP
        CarfuCommandRouter.match("Chỉ đường đến Mỹ Đình")!!.let {
            it.intent shouldBe CarfuIntent.NAVIGATE_PLACE
            it.place shouldBe "Mỹ Đình"
        }
    }
})
