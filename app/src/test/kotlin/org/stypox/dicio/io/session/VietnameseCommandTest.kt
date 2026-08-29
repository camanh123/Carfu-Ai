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
})

class CarfuCommandRouterTest : StringSpec({
    "routes the 12 CARFU commands with diacritics" {
        CarfuCommandRouter.match("Mở YouTube")!!.intent shouldBe CarfuIntent.OPEN_YOUTUBE
        CarfuCommandRouter.match("Mở bản đồ")!!.intent shouldBe CarfuIntent.OPEN_MAPS
        CarfuCommandRouter.match("Mở MusicLoop")!!.intent shouldBe CarfuIntent.OPEN_MUSICLOOP
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
})
