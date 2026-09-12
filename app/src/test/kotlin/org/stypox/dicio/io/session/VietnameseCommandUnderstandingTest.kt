package org.stypox.dicio.io.session

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Phase 2 — Vietnamese Command Understanding + candidate ownership regressions.
 */
class VietnameseCommandUnderstandingTest : StringSpec({
    beforeTest {
        SessionCommandDecision.resetForTests()
        VoiceSessionManager.resetForTests()
        VoiceTriggerManager.resetForTests()
    }

    fun nav(raw: String) = VietnameseCommandUnderstanding.understand(raw, sessionId = 1L)

    // --- NAVIGATION ---

    "1. Chỉ đường đến Mỹ Đình → NAVIGATE(Mỹ Đình)" {
        val u = nav("Chỉ đường đến Mỹ Đình")
        u.completeness shouldBe SemanticCompleteness.COMPLETE
        u.executable.shouldBeTrue()
        u.intent shouldBe VoiceIntent.NAVIGATE
        u.destination shouldBe "Mỹ Đình"
        u.command shouldBe CanonicalCommand.Navigate("Mỹ Đình")
        u.rawTranscript shouldBe "Chỉ đường đến Mỹ Đình"
        VietnameseCommandUnderstanding.confirmationSpeechVi(u.command!!) shouldContain "Mỹ Đình"
    }

    "2. Chỉ đường đến Hồ Gươm → NAVIGATE(Hồ Gươm)" {
        val u = nav("Chỉ đường đến Hồ Gươm")
        u.command shouldBe CanonicalCommand.Navigate("Hồ Gươm")
        u.executable.shouldBeTrue()
    }

    "3. Dẫn đường tới sân bay Nội Bài → NAVIGATE(sân bay Nội Bài)" {
        val u = nav("Dẫn đường tới sân bay Nội Bài")
        u.intent shouldBe VoiceIntent.NAVIGATE
        u.destination shouldBe "sân bay Nội Bài"
        u.command shouldBe CanonicalCommand.Navigate("sân bay Nội Bài")
    }

    "4. Đi đến Big C Thăng Long → NAVIGATE(Big C Thăng Long)" {
        val u = nav("Đi đến Big C Thăng Long")
        u.destination shouldBe "Big C Thăng Long"
        u.command shouldBe CanonicalCommand.Navigate("Big C Thăng Long")
    }

    "5. Chỉ đường đến → INCOMPLETE not executable" {
        val u = nav("Chỉ đường đến")
        u.completeness shouldBe SemanticCompleteness.INCOMPLETE
        u.executable.shouldBeFalse()
        u.command.shouldBeNull()
        u.intent shouldBe VoiceIntent.NAVIGATE
    }

    "6. Dẫn đường tới → INCOMPLETE not executable" {
        val u = nav("Dẫn đường tới")
        u.completeness shouldBe SemanticCompleteness.INCOMPLETE
        u.executable.shouldBeFalse()
        u.command.shouldBeNull()
    }

    // --- APP ---

    "7. Mở MusicLoop → OPEN_APP(MusicLoop)" {
        val u = nav("Mở MusicLoop")
        u.intent shouldBe VoiceIntent.OPEN_APP
        u.command shouldBe CanonicalCommand.OpenApp("MusicLoop")
        u.executable.shouldBeTrue()
    }

    "8. mở music look → MusicLoop via controlled alias" {
        val u = nav("mở music look")
        u.rawTranscript shouldBe "mở music look"
        u.command shouldBe CanonicalCommand.OpenApp("MusicLoop")
        u.completeness shouldBe SemanticCompleteness.COMPLETE
    }

    "9. Mở YouTube → OPEN_APP(YouTube)" {
        val u = nav("Mở YouTube")
        u.command shouldBe CanonicalCommand.OpenApp("YouTube")
    }

    "10. Mở Chrome → OPEN_APP(Chrome) understanding without Phase-2 launch mapping" {
        val u = nav("Mở Chrome")
        u.intent shouldBe VoiceIntent.OPEN_APP
        u.command shouldBe CanonicalCommand.OpenApp("Chrome")
        VietnameseCommandUnderstanding.toExecutableRoutedCommand(u.command!!).shouldBeNull()
    }

    // --- MEDIA ---

    "11. Mở bài Đừng xa em đêm nay trên YouTube → PLAY_MEDIA" {
        val u = nav("Mở bài Đừng xa em đêm nay trên YouTube")
        u.intent shouldBe VoiceIntent.PLAY_MEDIA
        u.query shouldBe "Đừng xa em đêm nay"
        u.provider shouldBe "YouTube"
        u.command shouldBe CanonicalCommand.PlayMedia("Đừng xa em đêm nay", "YouTube")
        VietnameseCommandUnderstanding.toExecutableRoutedCommand(u.command!!).shouldBeNull()
    }

    "12. Mở bài Nơi này có anh trên YouTube → PLAY_MEDIA" {
        val u = nav("Mở bài Nơi này có anh trên YouTube")
        u.command shouldBe CanonicalCommand.PlayMedia("Nơi này có anh", "YouTube")
    }

    "13. Mở bài trên YouTube → INCOMPLETE" {
        val u = nav("Mở bài trên YouTube")
        u.completeness shouldBe SemanticCompleteness.INCOMPLETE
        u.executable.shouldBeFalse()
        u.command.shouldBeNull()
        u.intent shouldBe VoiceIntent.PLAY_MEDIA
    }

    // --- CANDIDATE OWNERSHIP ---

    "14. partial Chỉ đường đến then final Mỹ Đình → destination Mỹ Đình never đến" {
        SessionCommandDecision.bindSession(10L)
        val partial = VietnameseCommandUnderstanding.understand("Chỉ đường đến", 10L)
        SessionCommandDecision.onPartial(10L, partial)
        SessionCommandDecision.provisional(10L)!!.executable.shouldBeFalse()

        val decision = SessionCommandDecision.decideFinal(
            10L,
            listOf(
                "Chỉ đường đến" to 0.95f,
                "Chỉ đường đến Mỹ Đình" to 0.80f,
            ),
        )
        decision.shouldNotBeNull()
        decision!!.command shouldBe CanonicalCommand.Navigate("Mỹ Đình")
        decision.destination shouldBe "Mỹ Đình"
        (decision.command as CanonicalCommand.Navigate).destination shouldBe "Mỹ Đình"
        // Particle destination must never win.
        decision.destination shouldBe "Mỹ Đình"
        VietnameseCommandUnderstanding.understand("Chỉ đường đến").executable.shouldBeFalse()
    }

    "15. partial Chỉ đường then Hồ Gươm final" {
        SessionCommandDecision.bindSession(11L)
        val decision = SessionCommandDecision.decideFinal(
            11L,
            listOf(
                "Chỉ đường" to 0.9f,
                "Chỉ đường đến Hồ Gươm" to 0.7f,
            ),
        )
        decision!!.command shouldBe CanonicalCommand.Navigate("Hồ Gươm")
    }

    "16. partial media then complete PLAY_MEDIA wins" {
        SessionCommandDecision.bindSession(12L)
        val decision = SessionCommandDecision.decideFinal(
            12L,
            listOf(
                "Mở bài Đừng xa em" to 0.9f,
                "Mở bài Đừng xa em đêm nay trên YouTube" to 0.7f,
            ),
        )
        decision!!.command.shouldBeInstanceOf<CanonicalCommand.PlayMedia>()
        val media = decision.command as CanonicalCommand.PlayMedia
        media.query shouldBe "Đừng xa em đêm nay"
        media.provider shouldBe "YouTube"
    }

    // --- SESSION SAFETY ---

    "17. stale other-session candidate cannot lock current CanonicalCommand" {
        SessionCommandDecision.bindSession(20L)
        val foreign = VietnameseCommandUnderstanding.understand(
            "Chỉ đường đến Mỹ Đình",
            sessionId = 999L,
        )
        // Wrong session id must not write into the bound slot.
        SessionCommandDecision.lockFinal(999L, foreign).shouldBeNull()
        SessionCommandDecision.locked(20L).shouldBeNull()

        val ok = SessionCommandDecision.decideFinal(
            20L,
            listOf("Chỉ đường đến Hồ Gươm" to 1f),
        )
        ok!!.destination shouldBe "Hồ Gươm"
    }

    "18. cancelled session + late final → no executable command" {
        SessionCommandDecision.bindSession(30L)
        SessionCommandDecision.markCancelled(30L)
        val late = SessionCommandDecision.decideFinal(
            30L,
            listOf("Chỉ đường đến Mỹ Đình" to 1f),
        )
        late.shouldBeNull()
        SessionCommandDecision.isCancelled(30L).shouldBeTrue()
    }

    "OPEN_APP and PLAY_MEDIA are distinct for YouTube phrases" {
        nav("Mở YouTube").command shouldBe CanonicalCommand.OpenApp("YouTube")
        nav("Mở bài Đừng xa em đêm nay trên YouTube").intent shouldBe VoiceIntent.PLAY_MEDIA
    }

    "same CanonicalCommand drives TTS and Maps payload for navigate" {
        val u = nav("Chỉ đường đến Mỹ Đình")
        val cmd = u.command!!
        val speech = VietnameseCommandUnderstanding.confirmationSpeechVi(cmd)!!
        speech shouldContain "Mỹ Đình"
        val routed = VietnameseCommandUnderstanding.toExecutableRoutedCommand(cmd)!!
        routed.place shouldBe "Mỹ Đình"
        routed.place shouldBe (cmd as CanonicalCommand.Navigate).destination
    }

    "weaker late callback cannot downgrade locked decision" {
        SessionCommandDecision.bindSession(40L)
        val strong = SessionCommandDecision.decideFinal(
            40L,
            listOf("Chỉ đường đến Mỹ Đình" to 0.8f),
        )!!
        val weak = VietnameseCommandUnderstanding.understand("Chỉ đường đến", 40L)
        val after = SessionCommandDecision.tryAcceptLate(40L, weak)!!
        after.command shouldBe strong.command
        after.destination shouldBe "Mỹ Đình"
    }

    "Mở alone is incomplete OPEN_APP" {
        val u = nav("Mở")
        u.completeness shouldBe SemanticCompleteness.INCOMPLETE
        u.executable.shouldBeFalse()
    }

    "nearby cafe query is unsupported place-search, not PlayMedia" {
        val u = nav("Tìm cho tôi quán cà phê nào gần nhất")
        u.command.shouldBeNull()
        u.intent shouldBe VoiceIntent.UNKNOWN
        u.reason shouldBe "unsupported_place_search"
        u.executable.shouldBeFalse()
        VietnameseCommandUnderstanding.isUnsupportedPlaceOrNearbyQuery(
            "Tìm cho tôi quán cà phê nào gần nhất",
        ).shouldBeTrue()
    }

    "nearby fishing-lake query is unsupported place-search, not PlayMedia" {
        val u = nav("Tìm cho tôi hồ câu nào gần nhất")
        u.command.shouldBeNull()
        u.intent shouldBe VoiceIntent.UNKNOWN
        u.reason shouldBe "unsupported_place_search"
        u.executable.shouldBeFalse()
    }

    "tìm bài on YouTube remains PlayMedia" {
        val u = nav("Tìm bài Đừng Xa Em Đêm Nay trên YouTube")
        u.intent shouldBe VoiceIntent.PLAY_MEDIA
        u.reason shouldNotBe "unsupported_place_search"
    }
})
