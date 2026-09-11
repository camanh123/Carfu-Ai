package org.stypox.dicio.io.session

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.stypox.dicio.skills.carfu.nlu.NavigationAddressNormalizer
import org.stypox.dicio.skills.carfu.nlu.NavigationCandidateRelation
import org.stypox.dicio.skills.carfu.nlu.NavigationCandidateTracker
import org.stypox.dicio.skills.carfu.nlu.NavigationCommitPolicy

/**
 * Natural Vietnamese NAV understanding: incomplete heads, growing partial streams,
 * shrink/correction, session isolation, and one committed Navigate per VoiceSession.
 */
class NavigationNaturalAddressTest : StringSpec({
    beforeTest {
        StableCompletePartialTracker.resetForTests()
        SessionCommandDecision.resetForTests()
        CanonicalActionGate.resetForTests()
        CommandSessionOutcome.resetForTests()
        VoiceSessionManager.resetForTests()
        CarfuDiag.clear()
        NavigationCandidateTracker.reset()
    }

    val window = NavigationCommitPolicy.NAV_STABILIZATION_MS

    fun u(raw: String, sid: Long = 1L) =
        VietnameseCommandUnderstanding.understand(raw, sessionId = sid)

    fun dest(raw: String): String =
        (u(raw).command as CanonicalCommand.Navigate).destination

    fun feed(sid: Long, raw: String, atMs: Long): StableCompletePartialTracker.Observe =
        StableCompletePartialTracker.onPartial(sid, u(raw, sid), atMs, sid)

    fun commitCount(obs: List<StableCompletePartialTracker.Observe>): Int =
        obs.count { it.decision == StableCompletePartialTracker.Decision.COMMIT }

    "NAV_STABILIZATION_MS is 800ms in the 700-1000 band" {
        window shouldBe 800L
        StableCompletePartialPolicy.NAV_STABILIZATION_MS shouldBe 800L
        VoiceToActionLatencyPolicy.navStabilizationMs() shouldBe 800L
    }

    "A. growing ngõ 112 Trung Kính commits once with the final destination" {
        StableCompletePartialTracker.bind(1L)
        val stream = listOf(
            "chỉ đường đến",
            "chỉ đường đến ngõ",
            "chỉ đường đến ngõ 112",
            "chỉ đường đến ngõ 112 trung",
            "chỉ đường đến ngõ 112 trung kính",
        )
        val observed = stream.mapIndexed { index, raw ->
            feed(1L, raw, index * 250L)
        }
        commitCount(observed) shouldBeExactly 0
        observed[0].decision shouldBe StableCompletePartialTracker.Decision.IGNORE
        observed[1].decision shouldBe StableCompletePartialTracker.Decision.IGNORE
        observed[2].decision shouldBe StableCompletePartialTracker.Decision.WAIT
        observed[3].decision shouldBe StableCompletePartialTracker.Decision.WAIT
        observed[4].decision shouldBe StableCompletePartialTracker.Decision.WAIT
        val commit = StableCompletePartialTracker.onTimer(1L, 1000L + window)
        commit.decision shouldBe StableCompletePartialTracker.Decision.COMMIT
        (commit.result?.command as CanonicalCommand.Navigate).destination shouldBe
            dest("chỉ đường đến ngõ 112 trung kính")
        VietnameseTranscript.foldForMatch(
            (commit.result?.command as CanonicalCommand.Navigate).destination,
        ) shouldBe "ngo 112 trung kinh"
        StableCompletePartialTracker.onPartial(
            1L,
            u("chỉ đường đến ngõ 112 trung kính hà nội"),
            1000L + window + 10L,
            1L,
        ).decision shouldBe StableCompletePartialTracker.Decision.IGNORE
    }

    "B. số 25 phố Huế does not commit the incomplete phố head" {
        StableCompletePartialTracker.bind(2L)
        feed(2L, "đi đến số", 0L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
        feed(2L, "đi đến số 25", 250L).decision shouldBe
            StableCompletePartialTracker.Decision.WAIT
        val streetHead = feed(2L, "đi đến số 25 phố", 500L)
        streetHead.decision shouldBe StableCompletePartialTracker.Decision.WAIT
        streetHead.fingerprint shouldBe "NAVIGATE|số 25"
        NavigationCandidateTracker.isBlockedByIncompleteGrowth().shouldBeTrue()
        feed(2L, "đi đến số 25 phố Huế", 750L).decision shouldBe
            StableCompletePartialTracker.Decision.WAIT
        NavigationCandidateTracker.isBlockedByIncompleteGrowth().shouldBeFalse()
        val commit = StableCompletePartialTracker.onTimer(2L, 750L + window)
        commit.decision shouldBe StableCompletePartialTracker.Decision.COMMIT
        (commit.result?.command as CanonicalCommand.Navigate).destination shouldBe "số 25 phố Huế"
    }

    "C. bệnh viện waits until Bạch Mai" {
        StableCompletePartialTracker.bind(3L)
        u("dẫn đường đến bệnh viện").completeness shouldBe SemanticCompleteness.INCOMPLETE
        feed(3L, "dẫn đường đến bệnh viện", 0L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
        feed(3L, "dẫn đường đến bệnh viện Bạch", 300L).decision shouldBe
            StableCompletePartialTracker.Decision.WAIT
        feed(3L, "dẫn đường đến bệnh viện Bạch Mai", 600L).decision shouldBe
            StableCompletePartialTracker.Decision.WAIT
        val commit = StableCompletePartialTracker.onTimer(3L, 600L + window)
        (commit.result?.command as CanonicalCommand.Navigate).destination shouldBe
            "bệnh viện Bạch Mai"
        commit.decision shouldBe StableCompletePartialTracker.Decision.COMMIT
    }

    "D. cây xăng gần waits until Mỹ Đình" {
        StableCompletePartialTracker.bind(4L)
        feed(4L, "chỉ đường tới cây xăng", 0L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
        feed(4L, "chỉ đường tới cây xăng gần", 250L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
        feed(4L, "chỉ đường tới cây xăng gần Mỹ Đình", 500L).decision shouldBe
            StableCompletePartialTracker.Decision.WAIT
        val commit = StableCompletePartialTracker.onTimer(4L, 500L + window)
        commit.decision shouldBe StableCompletePartialTracker.Decision.COMMIT
        (commit.result?.command as CanonicalCommand.Navigate).destination shouldBe
            "cây xăng gần Mỹ Đình"
    }

    "E. long university destination is preserved in full" {
        val raw = "đi đến trường Đại học Quốc Gia Hà Nội"
        val result = u(raw)
        result.completeness shouldBe SemanticCompleteness.COMPLETE
        result.command shouldBe CanonicalCommand.Navigate("trường Đại học Quốc Gia Hà Nội")
        StableCompletePartialTracker.bind(5L)
        feed(5L, raw, 0L).decision shouldBe StableCompletePartialTracker.Decision.WAIT
        val commit = StableCompletePartialTracker.onTimer(5L, window)
        (commit.result?.command as CanonicalCommand.Navigate).destination shouldBe
            "trường Đại học Quốc Gia Hà Nội"
    }

    "F. short Mỹ Đình commits after the window with no EOS" {
        StableCompletePartialTracker.bind(6L)
        val first = feed(6L, "chỉ đường đến Mỹ Đình", 0L)
        first.decision shouldBe StableCompletePartialTracker.Decision.WAIT
        first.eosConfirmed.shouldBeFalse()
        StableCompletePartialTracker.onEndOfSpeech(6L, 20L, 6L).decision shouldBe
            StableCompletePartialTracker.Decision.WAIT
        val commit = StableCompletePartialTracker.onTimer(6L, window)
        commit.decision shouldBe StableCompletePartialTracker.Decision.COMMIT
        commit.reason shouldBe "semantic_navigate_stable"
        (commit.result?.command as CanonicalCommand.Navigate).destination shouldBe "Mỹ Đình"
    }

    "G. shrinking STT correction does not immediately downgrade" {
        StableCompletePartialTracker.bind(7L)
        feed(7L, "chỉ đường đến ngõ 112 Trung Kính", 0L).decision shouldBe
            StableCompletePartialTracker.Decision.WAIT
        val shrunk = feed(7L, "chỉ đường đến ngõ 112", 100L)
        shrunk.decision shouldBe StableCompletePartialTracker.Decision.WAIT
        shrunk.fingerprint shouldBe "NAVIGATE|ngõ 112 Trung Kính"
        NavigationCandidateTracker.lastRelation() shouldBe NavigationCandidateRelation.SHRUNK
        val commit = StableCompletePartialTracker.onTimer(7L, window)
        commit.decision shouldBe StableCompletePartialTracker.Decision.COMMIT
        (commit.result?.command as CanonicalCommand.Navigate).destination shouldBe
            "ngõ 112 Trung Kính"
    }

    "H. new VoiceSession does not leak the previous destination" {
        StableCompletePartialTracker.bind(8L)
        feed(8L, "chỉ đường đến Mỹ Đình", 0L)
        StableCompletePartialTracker.bind(9L)
        NavigationCandidateTracker.preferredResult().shouldBeNull()
        StableCompletePartialTracker.hasPendingStabilityWork().shouldBeFalse()
        val second = feed(9L, "dẫn đường đến Hồ Gươm", 0L)
        second.fingerprint shouldBe "NAVIGATE|Hồ Gươm"
        val commit = StableCompletePartialTracker.onTimer(9L, window)
        (commit.result?.command as CanonicalCommand.Navigate).destination shouldBe "Hồ Gươm"
    }

    "I. cancel during stabilization produces no action" {
        StableCompletePartialTracker.bind(10L)
        feed(10L, "chỉ đường đến Mỹ Đình", 0L).decision shouldBe
            StableCompletePartialTracker.Decision.WAIT
        StableCompletePartialTracker.markCancelled()
        StableCompletePartialTracker.onTimer(10L, window).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
        StableCompletePartialTracker.committedForTests().shouldBeFalse()
        NavigationCandidateTracker.preferredResult().shouldBeNull()
    }

    "exactly one Navigate per VoiceSession after late partials" {
        StableCompletePartialTracker.bind(11L)
        SessionCommandDecision.bindSession(11L)
        CanonicalActionGate.bind(11L)
        feed(11L, "chỉ đường tới Mỹ Đình", 0L)
        val locked = SessionCommandDecision.lockFinal(
            11L,
            StableCompletePartialTracker.onTimer(11L, window).result!!.copy(executable = true),
        )
        locked!!.command shouldBe CanonicalCommand.Navigate("Mỹ Đình")
        CanonicalActionGate.tryClaim(11L).shouldBeTrue()
        CanonicalActionGate.tryClaim(11L).shouldBeFalse()
        StableCompletePartialTracker.onTimer(11L, window + 50L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
        feed(11L, "chỉ đường tới Hồ Gươm", window + 80L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
    }

    "arbitrary natural addresses keep every address token" {
        listOf(
            "Chỉ đường đến ngõ 112 Trung Kính" to "ngõ 112 Trung Kính",
            "Đi đến số 25 phố Huế" to "số 25 phố Huế",
            "Dẫn đường đến 123 Nguyễn Trãi Thanh Xuân" to "123 Nguyễn Trãi Thanh Xuân",
            "Chỉ đường tới bệnh viện Bạch Mai" to "bệnh viện Bạch Mai",
            "Đi đến trường Đại học Quốc Gia Hà Nội" to "trường Đại học Quốc Gia Hà Nội",
            "Dẫn đường đến quán cà phê ABC ở Cầu Giấy" to "quán cà phê ABC ở Cầu Giấy",
            "Chỉ đường tới cây xăng gần Mỹ Đình" to "cây xăng gần Mỹ Đình",
            "Đi đến ngõ 80 Chùa Láng số 10" to "ngõ 80 Chùa Láng số 10",
            "Dẫn đường tới số nhà 42 ngõ 12 phố Trần Thái Tông" to
                "số nhà 42 ngõ 12 phố Trần Thái Tông",
            "Chỉ đường đến Chợ Hôm" to "Chợ Hôm",
            "Đưa tôi đến Hồ Văn Quán" to "Hồ Văn Quán",
            "Chỉ đường đến Đại học Văn Lang" to "Đại học Văn Lang",
            "Tìm đường đến Mỹ Đình" to "Mỹ Đình",
            "Đưa tôi tới Hồ Gươm" to "Hồ Gươm",
        ).forEach { (raw, expected) ->
            val result = u(raw)
            withClue("$raw reason=${result.reason} cmd=${result.command}") {
                result.completeness shouldBe SemanticCompleteness.COMPLETE
                result.executable.shouldBeTrue()
                result.command shouldBe CanonicalCommand.Navigate(expected)
            }
        }
    }

    "incomplete address heads are not executable" {
        listOf(
            "chỉ đường đến ngõ",
            "đi đến số",
            "dẫn đường đến đường",
            "đi đến phố",
            "chỉ đường tới quận",
            "dẫn đường đến bệnh viện",
            "chỉ đường tới cây xăng",
            "đi đến trường",
            "chỉ đường đến chợ",
            "đi đến quán",
        ).forEach { raw ->
            val result = u(raw)
            result.intent shouldBe VoiceIntent.NAVIGATE
            result.completeness shouldBe SemanticCompleteness.INCOMPLETE
            result.executable.shouldBeFalse()
            StableCompletePartialPolicy.isEligible(result).shouldBeFalse()
        }
    }

    "TTS confirmation uses only the committed destination" {
        val committed = CanonicalCommand.Navigate("ngõ 112 Trung Kính")
        VietnameseCommandUnderstanding.confirmationSpeechVi(committed) shouldBe
            "Đang chỉ đường đến ngõ 112 Trung Kính"
        VietnameseCommandUnderstanding.confirmationSpeechVi(CanonicalCommand.Navigate("ngõ")) shouldBe
            "Đang chỉ đường đến ngõ"
        u("chỉ đường đến ngõ").completeness shouldBe SemanticCompleteness.INCOMPLETE
        u("chỉ đường đến ngõ").executable.shouldBeFalse()
    }

    "spoken numbers convert only at high confidence" {
        VietnameseTranscript.foldForMatch(
            NavigationAddressNormalizer.parse("chỉ đường đến ngõ một trăm mười hai Trung Kính")
                .destination,
        ) shouldBe "ngo 112 trung kinh"
        NavigationAddressNormalizer.parse("đi đến hai mươi lăm phố Huế").destination.let {
            VietnameseTranscript.foldForMatch(it) shouldBe "25 pho hue"
        }
        NavigationAddressNormalizer.parse("chỉ đường đến đường Nam Kỳ").destination.let { dest ->
            VietnameseTranscript.foldForMatch(dest) shouldContain "nam"
            VietnameseTranscript.foldForMatch(dest).shouldNotContain("5")
        }
    }

    "relational destination phrases are preserved" {
        dest("chỉ đường tới bệnh viện gần đây") shouldBe "bệnh viện gần đây"
        dest("dẫn đường đến chợ gần Hồ Gươm") shouldBe "chợ gần Hồ Gươm"
        dest("đi đến quán cà phê ABC ở Cầu Giấy") shouldBe "quán cà phê ABC ở Cầu Giấy"
    }

    "polite fillers are stripped without eating address words" {
        dest("giúp tôi chỉ đường đến Mỹ Đình") shouldBe "Mỹ Đình"
        dest("chỉ đường đến Mỹ Đình nhé") shouldBe "Mỹ Đình"
        dest("đi đến Mỹ Đình đi") shouldBe "Mỹ Đình"
    }

    "NAV diagnostics name candidate relation and completeness" {
        CarfuDiag.clear()
        StableCompletePartialTracker.bind(12L)
        feed(12L, "chỉ đường đến Hồ Gươm", 10L)
        val lines = CarfuDiag.recent(CarfuDiag.TAG_VOICE)
        val candidate = lines.last { it.contains("NAV_CANDIDATE SESSION_ID=12") }
        candidate shouldContain "SR_RAW_PARTIAL=chỉ đường đến Hồ Gươm"
        candidate shouldContain "NORMALIZED_DESTINATION=Hồ Gươm"
        candidate shouldContain "PREFERRED_DESTINATION=Hồ Gươm"
        candidate shouldContain "CANDIDATE_RELATION="
        candidate shouldContain "CANDIDATE_CHANGED="
        candidate shouldContain "TIMER_SOURCE=PARTIAL"
        candidate shouldContain "NAV_COMPLETENESS=COMPLETE"
        val stabilize = lines.last { it.contains("NAV_STABILIZE") }
        stabilize shouldContain "SESSION_ID=12"
        stabilize shouldContain "NORMALIZED_DESTINATION=Hồ Gươm"
        stabilize shouldContain "TIMER_SOURCE=PARTIAL"
        stabilize shouldContain "CANDIDATE_RELATION="
        StableCompletePartialTracker.onTimer(12L, 10L + window)
        val timerLine = CarfuDiag.recent(CarfuDiag.TAG_VOICE).last { it.contains("TIMER_SOURCE=STABILITY_TIMER") }
        timerLine shouldContain "CANDIDATE_CHANGED=false"
        val committed = CarfuDiag.recent(CarfuDiag.TAG_VOICE).last { it.contains("NAV_COMMIT") }
        committed shouldContain "FINAL_DESTINATION=Hồ Gươm"
        committed shouldContain "FINAL_URI="
        committed shouldContain "ACTION_COUNT=1"
        committed shouldContain "NAV_COMMIT_REASON=semantic_navigate_stable"
    }

    "R2. Hồ Văn Quán keeps Quán — not truncated to Hồ Văn" {
        val raw = "đưa tôi đến Hồ Văn Quán"
        val parsed = NavigationAddressNormalizer.parse(raw)
        parsed.destination shouldBe "Hồ Văn Quán"
        parsed.destinationFolded shouldBe "ho van quan"
        val result = u(raw)
        result.completeness shouldBe SemanticCompleteness.COMPLETE
        result.executable.shouldBeTrue()
        result.command shouldBe CanonicalCommand.Navigate("Hồ Văn Quán")
        NavigationCommitPolicy.isIncompleteDestination("Hồ Văn Quán").shouldBeFalse()
        NavigationCommitPolicy.isIncompleteDestination("Hồ Văn").shouldBeFalse()
    }

    "R2. 800ms timer advances without mutating candidateChangedAt" {
        StableCompletePartialTracker.bind(21L)
        feed(21L, "đưa tôi đến Hồ Văn Quán", 0L).decision shouldBe
            StableCompletePartialTracker.Decision.WAIT
        val changedAt = NavigationCandidateTracker.candidateChangedAt()
        changedAt shouldBe 0L
        val mid = StableCompletePartialTracker.onTimer(21L, 400L)
        mid.decision shouldBe StableCompletePartialTracker.Decision.WAIT
        mid.remainingMs shouldBe 400L
        NavigationCandidateTracker.candidateChangedAt() shouldBe changedAt
        NavigationCandidateTracker.stableForMs(400L) shouldBe 400L
        val commit = StableCompletePartialTracker.onTimer(21L, window)
        commit.decision shouldBe StableCompletePartialTracker.Decision.COMMIT
        commit.reason shouldBe "semantic_navigate_stable"
        NavigationCandidateTracker.candidateChangedAt() shouldBe changedAt
        (commit.result?.command as CanonicalCommand.Navigate).destination shouldBe "Hồ Văn Quán"
    }

    "R2. growing Hồ → Hồ Văn → Hồ Văn Quán resets candidateChangedAt once per extension" {
        StableCompletePartialTracker.bind(22L)
        feed(22L, "đưa tôi đến Hồ", 0L)
        val afterHo = NavigationCandidateTracker.candidateChangedAt()
        feed(22L, "đưa tôi đến Hồ Văn", 250L)
        val afterVan = NavigationCandidateTracker.candidateChangedAt()
        afterVan shouldBe 250L
        (afterVan > afterHo).shouldBeTrue()
        feed(22L, "đưa tôi đến Hồ Văn Quán", 500L)
        val afterQuan = NavigationCandidateTracker.candidateChangedAt()
        afterQuan shouldBe 500L
        (afterQuan > afterVan).shouldBeTrue()
        NavigationCandidateTracker.preferredResult()!!.command shouldBe
            CanonicalCommand.Navigate("Hồ Văn Quán")
        val commit = StableCompletePartialTracker.onTimer(22L, 500L + window)
        commit.decision shouldBe StableCompletePartialTracker.Decision.COMMIT
        (commit.result?.command as CanonicalCommand.Navigate).destination shouldBe "Hồ Văn Quán"
        NavigationCandidateTracker.candidateChangedAt() shouldBe afterQuan
    }

    "R2. identical repeated partials do not reset stabilization" {
        StableCompletePartialTracker.bind(23L)
        repeat(3) { i ->
            feed(23L, "đưa tôi đến Hồ Văn Quán", i * 100L)
        }
        NavigationCandidateTracker.candidateChangedAt() shouldBe 0L
        NavigationCandidateTracker.lastRelation() shouldBe NavigationCandidateRelation.UNCHANGED
        NavigationCandidateTracker.stableForMs(300L) shouldBe 300L
        val commit = StableCompletePartialTracker.onTimer(23L, window)
        commit.decision shouldBe StableCompletePartialTracker.Decision.COMMIT
        (commit.result?.command as CanonicalCommand.Navigate).destination shouldBe "Hồ Văn Quán"
    }

    "R2. case-only partials do not reset semantic stability" {
        StableCompletePartialTracker.bind(24L)
        feed(24L, "đi đến Smart", 0L)
        val changedAt = NavigationCandidateTracker.candidateChangedAt()
        feed(24L, "đi đến smart", 120L)
        NavigationCandidateTracker.candidateChangedAt() shouldBe changedAt
        NavigationCandidateTracker.lastRelation() shouldBe NavigationCandidateRelation.UNCHANGED
        feed(24L, "đi đến Smart", 240L)
        NavigationCandidateTracker.candidateChangedAt() shouldBe changedAt
        val commit = StableCompletePartialTracker.onTimer(24L, window)
        commit.decision shouldBe StableCompletePartialTracker.Decision.COMMIT
        VietnameseTranscript.foldForMatch(
            (commit.result?.command as CanonicalCommand.Navigate).destination,
        ) shouldBe "smart"
    }
})
