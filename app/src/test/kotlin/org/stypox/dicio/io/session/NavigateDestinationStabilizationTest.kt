package org.stypox.dicio.io.session

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.stypox.dicio.io.input.CommandRecognitionPolicy

/**
 * NAV-only destination stabilization: growing addresses must not commit a prefix.
 */
class NavigateDestinationStabilizationTest : StringSpec({
    beforeTest {
        StableCompletePartialTracker.resetForTests()
        SessionCommandDecision.resetForTests()
        CanonicalActionGate.resetForTests()
        CommandSessionOutcome.resetForTests()
        VoiceSessionManager.resetForTests()
        CarfuDiag.clear()
    }

    val window = StableCompletePartialPolicy.NAV_STABILIZATION_MS

    fun u(raw: String, sid: Long = 1L) =
        VietnameseCommandUnderstanding.understand(raw, sessionId = sid)

    fun waitNav(sid: Long, raw: String, atMs: Long, gen: Long = sid): StableCompletePartialTracker.Observe {
        val result = u(raw, sid)
        val obs = StableCompletePartialTracker.onPartial(sid, result, atMs, gen)
        obs.decision shouldBe StableCompletePartialTracker.Decision.WAIT
        obs.reason shouldBe "navigate_waiting_stable"
        obs.eosConfirmed.shouldBeFalse()
        return obs
    }

    "NAV_STABILIZATION_MS is a short NAV-only window" {
        window shouldBe 800L
        VoiceToActionLatencyPolicy.navStabilizationMs() shouldBe 800L
        window.shouldBeInRange(700L..1000L)
        window.shouldBeLessThan(CommandRecognitionPolicy.ANDROID_LISTEN_TIMEOUT_MS)
        StableCompletePartialPolicy.holdTimerMayCommit().shouldBeFalse()
        StableCompletePartialPolicy.STABILITY_MS shouldBe 0L
        StableCompletePartialPolicy.requiresEndOfSpeechForNavigate().shouldBeFalse()
    }

    "CASE 1 growing ngõ 112 Trung Kính does not commit prefixes" {
        StableCompletePartialTracker.bind(1L)
        val steps = listOf(
            "Chỉ đường đến" to null,
            "Chỉ đường đến ngõ" to null,
            "Chỉ đường đến ngõ 112" to "ngõ 112",
            "Chỉ đường đến ngõ 112 Trung" to "ngõ 112 Trung",
            "Chỉ đường đến ngõ 112 Trung Kính" to "ngõ 112 Trung Kính",
        )
        var t = 0L
        steps.forEachIndexed { index, (raw, dest) ->
            val result = u(raw)
            if (dest == null) {
                result.completeness shouldBe SemanticCompleteness.INCOMPLETE
                StableCompletePartialTracker.onPartial(1L, result, t, 1L).decision shouldBe
                    StableCompletePartialTracker.Decision.IGNORE
            } else {
                result.command shouldBe CanonicalCommand.Navigate(dest)
                val obs = StableCompletePartialTracker.onPartial(1L, result, t, 1L)
                obs.decision shouldBe StableCompletePartialTracker.Decision.WAIT
                obs.fingerprint shouldBe "NAVIGATE|$dest"
                StableCompletePartialTracker.committedForTests().shouldBeFalse()
            }
            t += 250L
        }
        val tooEarly = StableCompletePartialTracker.onTimer(1L, t)
        tooEarly.decision shouldBe StableCompletePartialTracker.Decision.WAIT
        val commit = StableCompletePartialTracker.onTimer(1L, t - 250L + window)
        commit.decision shouldBe StableCompletePartialTracker.Decision.COMMIT
        commit.reason shouldBe "semantic_navigate_stable"
        (commit.result?.command as CanonicalCommand.Navigate).destination shouldBe
            "ngõ 112 Trung Kính"
        StableCompletePartialTracker.onPartial(
            1L,
            u("Chỉ đường đến ngõ 112 Trung Kính Hà Nội"),
            t + window,
            1L,
        ).decision shouldBe StableCompletePartialTracker.Decision.IGNORE
    }

    "CASE 2 sân bay grows to Nội Bài before commit" {
        StableCompletePartialTracker.bind(2L)
        waitNav(2L, "Đi đến sân bay Nội", 300L)
        waitNav(2L, "Đi đến sân bay Nội Bài", 600L)
        StableCompletePartialTracker.onTimer(2L, 600L + window - 1L).decision shouldBe
            StableCompletePartialTracker.Decision.WAIT
        val commit = StableCompletePartialTracker.onTimer(2L, 600L + window)
        commit.decision shouldBe StableCompletePartialTracker.Decision.COMMIT
        commit.reason shouldBe "semantic_navigate_stable"
        (commit.result?.command as CanonicalCommand.Navigate).destination shouldBe
            "sân bay Nội Bài"
    }

    "CASE 3 short Mỹ Đình commits after the window without EOS" {
        StableCompletePartialTracker.bind(3L)
        val nav = u("Chỉ đường đến Mỹ Đình", 3L)
        nav.command shouldBe CanonicalCommand.Navigate("Mỹ Đình")
        val first = StableCompletePartialTracker.onPartial(3L, nav, 0L, 3L)
        first.decision shouldBe StableCompletePartialTracker.Decision.WAIT
        first.reason shouldBe "navigate_waiting_stable"
        first.eosConfirmed.shouldBeFalse()
        StableCompletePartialTracker.onEndOfSpeech(3L, 20L, 3L).decision shouldBe
            StableCompletePartialTracker.Decision.WAIT
        val commit = StableCompletePartialTracker.onTimer(3L, window)
        commit.decision shouldBe StableCompletePartialTracker.Decision.COMMIT
        commit.reason shouldBe "semantic_navigate_stable"
        window.shouldBeLessThan(CommandRecognitionPolicy.ANDROID_LISTEN_TIMEOUT_MS)
    }

    "long detailed addresses keep the full destination" {
        listOf(
            "Chỉ đường đến ngõ 112 Trung Kính" to "ngõ 112 Trung Kính",
            "Chỉ đường đến số 25 phố Huế" to "số 25 phố Huế",
            "Chỉ đường đến 123 Nguyễn Trãi Thanh Xuân" to "123 Nguyễn Trãi Thanh Xuân",
            "Đi đến số 10 ngõ 80 Chùa Láng" to "số 10 ngõ 80 Chùa Láng",
        ).forEach { (raw, dest) ->
            VietnameseCommandUnderstanding.understand(raw).command shouldBe
                CanonicalCommand.Navigate(dest)
        }
    }

    "new VoiceSession does not inherit previous NAV candidate or timer" {
        StableCompletePartialTracker.bind(1L)
        waitNav(1L, "Chỉ đường đến Mỹ Đình", 0L, 1L)
        StableCompletePartialTracker.bind(2L)
        StableCompletePartialTracker.hasPendingStabilityWork().shouldBeFalse()
        StableCompletePartialTracker.committedForTests().shouldBeFalse()
        StableCompletePartialTracker.onTimer(1L, window).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
        waitNav(2L, "Dẫn đường đến Hồ Gươm", 0L, 2L)
        val commit = StableCompletePartialTracker.onTimer(2L, window)
        commit.decision shouldBe StableCompletePartialTracker.Decision.COMMIT
        commit.fingerprint shouldBe "NAVIGATE|Hồ Gươm"
    }

    "cancelled session cannot fire delayed NAV commit" {
        StableCompletePartialTracker.bind(4L)
        waitNav(4L, "Chỉ đường đến bia bà", 0L, 4L)
        StableCompletePartialTracker.markCancelled()
        StableCompletePartialTracker.onTimer(4L, window).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
        StableCompletePartialTracker.committedForTests().shouldBeFalse()
    }

    "exactly one Navigate action after stable commit" {
        StableCompletePartialTracker.bind(5L)
        SessionCommandDecision.bindSession(5L)
        CanonicalActionGate.bind(5L)
        val nav = u("Chỉ đường tới Mỹ Đình", 5L)
        StableCompletePartialTracker.onPartial(5L, nav, 0L, 5L)
        val locked = SessionCommandDecision.lockFinal(
            5L,
            StableCompletePartialTracker.onTimer(5L, window).result!!.copy(executable = true),
        )
        locked!!.command shouldBe CanonicalCommand.Navigate("Mỹ Đình")
        CanonicalActionGate.tryClaim(5L).shouldBeTrue()
        CanonicalActionGate.tryClaim(5L).shouldBeFalse()
        CommandSessionOutcome.claim(CommandSessionOutcome.Kind.EXECUTED).shouldBeTrue()
        CommandSessionOutcome.claim(CommandSessionOutcome.Kind.EXECUTED).shouldBeFalse()
        StableCompletePartialTracker.onTimer(5L, window + 50L).decision shouldBe
            StableCompletePartialTracker.Decision.IGNORE
    }

    "NAV stabilize diagnostics name the current candidate" {
        CarfuDiag.clear()
        StableCompletePartialTracker.bind(6L)
        waitNav(6L, "Chỉ đường đến Hồ Gươm", 10L, 6L)
        val lines = CarfuDiag.recent(CarfuDiag.TAG_VOICE)
        val nav = lines.last { it.contains("NAV_STABILIZE") }
        nav shouldContain "SESSION_ID=6"
        nav shouldContain "NORMALIZED_DESTINATION=Hồ Gươm"
        nav shouldContain "NAV_FINGERPRINT=NAVIGATE|Hồ Gươm"
        nav shouldContain "NAV_COMMIT_REASON=navigate_waiting_stable"
        nav shouldContain "CANDIDATE_CHANGED_AT=10"
        nav shouldContain "TIMER_SOURCE=PARTIAL"
        StableCompletePartialTracker.onTimer(6L, 10L + window)
        val committed = CarfuDiag.recent(CarfuDiag.TAG_VOICE).last { it.contains("NAV_STABILIZE") }
        committed shouldContain "NAV_COMMIT_REASON=semantic_navigate_stable"
        committed shouldContain "NAV_STABLE_FOR_MS=$window"
        committed shouldContain "TIMER_SOURCE=STABILITY_TIMER"
        committed shouldContain "CANDIDATE_CHANGED=false"
    }
})
