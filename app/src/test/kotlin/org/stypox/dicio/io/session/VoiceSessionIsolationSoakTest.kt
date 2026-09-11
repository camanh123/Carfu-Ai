package org.stypox.dicio.io.session

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.stypox.dicio.skills.carfu.nlu.NavigationCandidateTracker

private enum class SoakKind {
    NAVIGATE,
    OPEN_APP,
    UNSUPPORTED,
    PLAY_MEDIA,
    CANCEL,
    TIMEOUT,
}

/**
 * Deterministic multi-session isolation: soak, late callbacks, rapid MODE.
 * No Android SpeechRecognizer / Maps / YouTube / Media grammar.
 */
class VoiceSessionIsolationSoakTest : StringSpec({
    val clock = object {
        var now: Long = 1_000_000L
        fun advance(by: Long) {
            now += by
        }
    }

    beforeTest {
        VoiceTriggerManager.resetForTests()
        VoiceSessionManager.resetForTests()
        VoiceOnlinePolicy.resetForTests()
        CarfuSessionGate.resetForTests()
        CarfuActivationSource.resetForTests()
        clock.now = 1_000_000L
        VoiceTriggerManager.nowMs = { clock.now }
        VoiceSessionManager.nowMs = { clock.now }
        CarfuSessionGate.nowMs = { clock.now }
        CarfuSessionGate.setBackgroundWakeEnabled(true)
        VoiceOnlinePolicy.onlineOverride = true
    }

    fun startSession(sessionId: Long): VoiceSessionManager.Session {
        VoiceTriggerManager.clearHardwareDebounce()
        val trigger = VoiceTriggerManager.request(VoiceTriggerManager.Origin.HARDWARE_MODE)
        trigger.accepted.shouldBeTrue()
        val session = VoiceSessionManager.createFromTrigger(trigger.trigger!!, sessionId)
        session.shouldNotBeNull()
        SessionCommandDecision.bindSession(sessionId)
        CanonicalActionGate.bind(sessionId)
        CommandSessionOutcome.bind(sessionId)
        StableCompletePartialTracker.bind(sessionId)
        VoiceSessionManager.requestListen(sessionId).shouldBeTrue()
        VoiceSessionManager.hasLiveSession().shouldBeTrue()
        VoiceSessionManager.liveSession()!!.sessionId shouldBe sessionId
        return session!!
    }

    fun assertTerminalIsolation() {
        VoiceSessionManager.hasLiveSession().shouldBeFalse()
        VoiceSessionGuard.pendingNavTimerCount() shouldBe 0
        NavigationCandidateTracker.preferredResult().shouldBeNull()
        SessionCommandDecision.hasLockedCommandForTests().shouldBeFalse()
        CanonicalActionGate.claimedForTests().shouldBeFalse()
        CanonicalActionGate.boundSessionIdForTests() shouldBe 0L
        SessionCommandDecision.boundSessionIdForTests() shouldBe 0L
        VoiceSessionManager.liveSession().shouldBeNull()
    }

    fun runKind(sessionId: Long, kind: SoakKind) {
        startSession(sessionId)
        when (kind) {
            SoakKind.NAVIGATE -> {
                VoiceSessionGuard.armNavTimer(sessionId)
                val raw = "Chỉ đường đến sân vận động Mỹ Đình"
                val u = VietnameseCommandUnderstanding.understand(raw, sessionId)
                u.intent shouldBe VoiceIntent.NAVIGATE
                SessionCommandDecision.lockFinal(sessionId, u).shouldNotBeNull()
                CanonicalActionGate.tryClaim(sessionId).shouldBeTrue()
                CanonicalActionGate.tryClaim(sessionId).shouldBeFalse()
                VoiceSessionManager.requestExecution(sessionId).shouldBeTrue()
                VoiceSessionManager.counters().executionStarts shouldBeLessThanOrEqual 1
                CanonicalActionGate.markCompleted(sessionId)
                VoiceSessionManager.onExecutionDone(sessionId)
                VoiceSessionManager.terminate(sessionId, "complete")
            }
            SoakKind.OPEN_APP -> {
                val u = VietnameseCommandUnderstanding.understand("Mở YouTube", sessionId)
                u.command shouldBe CanonicalCommand.OpenApp("YouTube")
                SessionCommandDecision.lockFinal(sessionId, u)
                CanonicalActionGate.tryClaim(sessionId).shouldBeTrue()
                CanonicalActionGate.tryClaim(sessionId).shouldBeFalse()
                VoiceSessionManager.requestExecution(sessionId)
                VoiceSessionManager.counters().executionStarts shouldBeLessThanOrEqual 1
                CanonicalActionGate.markCompleted(sessionId)
                VoiceSessionManager.onExecutionDone(sessionId)
                VoiceSessionManager.terminate(sessionId, "complete")
            }
            SoakKind.UNSUPPORTED -> {
                val raw = if (sessionId % 2L == 0L) {
                    "Tìm cho tôi quán cà phê nào gần nhất"
                } else {
                    "Tìm cho tôi hồ câu nào gần nhất"
                }
                val u = VietnameseCommandUnderstanding.understand(raw, sessionId)
                u.reason shouldBe "unsupported_place_search"
                u.command.shouldBeNull()
                CanonicalActionGate.tryClaim(sessionId).shouldBeTrue()
                CommandSessionOutcome.claim(
                    CommandSessionOutcome.Kind.UNSUPPORTED,
                    sessionId,
                ).shouldBeTrue()
                VoiceSessionManager.terminate(sessionId, "unsupported_place_search")
            }
            SoakKind.PLAY_MEDIA -> {
                val raw = "Mở bài Đừng Xa Em Đêm Nay trên YouTube"
                val u = VietnameseCommandUnderstanding.understand(raw, sessionId)
                u.intent shouldBe VoiceIntent.PLAY_MEDIA
                SessionCommandDecision.lockFinal(sessionId, u)
                CanonicalActionGate.tryClaim(sessionId).shouldBeTrue()
                CanonicalActionGate.tryClaim(sessionId).shouldBeFalse()
                VoiceSessionManager.requestExecution(sessionId)
                VoiceSessionManager.counters().executionStarts shouldBeLessThanOrEqual 1
                CanonicalActionGate.markCompleted(sessionId)
                VoiceSessionManager.onExecutionDone(sessionId)
                VoiceSessionManager.terminate(sessionId, "complete")
            }
            SoakKind.CANCEL -> {
                VoiceSessionGuard.armNavTimer(sessionId)
                SessionCommandDecision.markCancelled(sessionId)
                CanonicalActionGate.markCancelled(sessionId)
                VoiceSessionManager.terminate(sessionId, "mode_toggle_cancel")
            }
            SoakKind.TIMEOUT -> {
                CommandSessionOutcome.claim(
                    CommandSessionOutcome.Kind.NO_SPEECH,
                    sessionId,
                ).shouldBeTrue()
                VoiceSessionManager.terminate(sessionId, "hard_timeout_or_silence")
            }
        }
        assertTerminalIsolation()
    }

    "routing audit of six natural commands" {
        data class Row(
            val raw: String,
            val intent: VoiceIntent,
            val commandCheck: (CanonicalCommand?) -> Unit,
            val reason: String? = null,
        )
        val rows = listOf(
            Row(
                "Chỉ đường đến sân vận động Mỹ Đình",
                VoiceIntent.NAVIGATE,
                { cmd ->
                    cmd.shouldBeInstanceOf<CanonicalCommand.Navigate>()
                    (cmd as CanonicalCommand.Navigate).destination shouldBe
                        "sân vận động Mỹ Đình"
                },
            ),
            Row(
                "Tìm cho tôi quán cà phê nào gần nhất",
                VoiceIntent.UNKNOWN,
                { it.shouldBeNull() },
                "unsupported_place_search",
            ),
            Row(
                "Tìm cho tôi hồ câu nào gần nhất",
                VoiceIntent.UNKNOWN,
                { it.shouldBeNull() },
                "unsupported_place_search",
            ),
            Row(
                "Mở YouTube",
                VoiceIntent.OPEN_APP,
                { it shouldBe CanonicalCommand.OpenApp("YouTube") },
            ),
            Row(
                "Mở bài Đừng Xa Em Đêm Nay trên YouTube",
                VoiceIntent.PLAY_MEDIA,
                { cmd ->
                    cmd.shouldBeInstanceOf<CanonicalCommand.PlayMedia>()
                    (cmd as CanonicalCommand.PlayMedia).query shouldBe "Đừng Xa Em Đêm Nay"
                    cmd.provider shouldBe "YouTube"
                },
            ),
            Row(
                "Chỉ đường đến số 2 ngõ 84 Trần Thái Tông",
                VoiceIntent.NAVIGATE,
                { cmd ->
                    cmd.shouldBeInstanceOf<CanonicalCommand.Navigate>()
                    (cmd as CanonicalCommand.Navigate).destination shouldBe
                        "số 2 ngõ 84 Trần Thái Tông"
                },
            ),
        )
        rows.forEachIndexed { index, row ->
            val sid = (index + 1).toLong()
            val u = VietnameseCommandUnderstanding.understand(row.raw, sid)
            u.rawTranscript shouldBe row.raw
            u.intent shouldBe row.intent
            u.sessionId shouldBe sid
            row.commandCheck(u.command)
            if (row.reason != null) u.reason shouldBe row.reason
            if (row.intent == VoiceIntent.UNKNOWN) {
                CarfuCommandRouter.match(row.raw)?.intent shouldBe CarfuIntent.SEARCH
            }
        }
    }

    "30 consecutive mixed sessions stay isolated" {
        val order = SoakKind.entries
        repeat(30) { i ->
            val kind = order[i % order.size]
            runKind(sessionId = (i + 1).toLong(), kind = kind)
        }
        VoiceSessionManager.hasLiveSession().shouldBeFalse()
    }

    "late callbacks from session A cannot mutate session B" {
        startSession(100L)
        VoiceSessionGuard.armNavTimer(100L)
        val nav = VietnameseCommandUnderstanding.understand(
            "Chỉ đường đến sân vận động Mỹ Đình",
            100L,
        )
        SessionCommandDecision.lockFinal(100L, nav)
        CanonicalActionGate.tryClaim(100L).shouldBeTrue()
        CommandSessionOutcome.claim(CommandSessionOutcome.Kind.EXECUTED, 100L).shouldBeTrue()
        VoiceSessionManager.terminate(100L, "complete")
        assertTerminalIsolation()

        val b = startSession(200L)
        b.sessionId shouldBe 200L

        VoiceSessionGuard.dropIfStale(100L, "partial").shouldBeTrue()
        VoiceSessionGuard.lastStaleDroppedForTests().shouldBeTrue()
        VoiceSessionGuard.dropIfStale(100L, "final").shouldBeTrue()
        VoiceSessionGuard.dropIfStale(100L, "nav_timer").shouldBeTrue()
        VoiceSessionGuard.dropIfStale(100L, "action_result").shouldBeTrue()
        VoiceSessionGuard.dropIfForeignLiveSession(100L, "end_wake_tts").shouldBeTrue()

        VoiceSessionManager.onLiveTranscript(100L, "stale partial").shouldBeFalse()
        SessionCommandDecision.lockFinal(100L, nav).shouldBeNull()
        SessionCommandDecision.onPartial(100L, nav)
        SessionCommandDecision.hasLockedCommandForTests().shouldBeFalse()
        CanonicalActionGate.tryClaim(100L).shouldBeFalse()
        CommandSessionOutcome.claim(CommandSessionOutcome.Kind.NO_SPEECH, 100L).shouldBeFalse()
        NavigationCandidateTracker.observe(100L, clock.now, nav).shouldBeNull()

        val stalePartial = StableCompletePartialTracker.onPartial(
            100L,
            nav,
            clock.now,
            generation = 1L,
        )
        stalePartial.decision shouldBe StableCompletePartialTracker.Decision.IGNORE

        VoiceSessionGuard.dropIfStale(200L, "partial").shouldBeFalse()
        CanonicalActionGate.tryClaim(200L).shouldBeTrue()
        CanonicalActionGate.tryClaim(200L).shouldBeFalse()
        VoiceSessionManager.liveSession()!!.sessionId shouldBe 200L
        VoiceSessionManager.terminate(200L, "complete")
        assertTerminalIsolation()
    }

    "rapid MODE cancel then a different command has no cooldown contamination" {
        startSession(1L)
        clock.advance(VoiceSessionManager.TOGGLE_CANCEL_MIN_AGE_MS + 1L)
        val cancel = VoiceTriggerManager.request(VoiceTriggerManager.Origin.HARDWARE_MODE)
        cancel.decision shouldBe VoiceTriggerManager.Decision.CANCEL_CURRENT
        VoiceSessionManager.terminate(1L, "mode_toggle_cancel")
        assertTerminalIsolation()

        val immediate = VoiceTriggerManager.request(VoiceTriggerManager.Origin.HARDWARE_MODE)
        immediate.accepted.shouldBeTrue()
        immediate.decision shouldBe VoiceTriggerManager.Decision.ACCEPTED
        val session = VoiceSessionManager.createFromTrigger(immediate.trigger!!, 2L)!!
        session.sessionId shouldBe 2L
        SessionCommandDecision.bindSession(2L)
        CanonicalActionGate.bind(2L)
        CommandSessionOutcome.bind(2L)
        StableCompletePartialTracker.bind(2L)
        VoiceSessionManager.requestListen(2L)

        SessionCommandDecision.lockFinal(1L, VietnameseCommandUnderstanding.understand(
            "Chỉ đường đến sân vận động Mỹ Đình",
            1L,
        )).shouldBeNull()
        val next = VietnameseCommandUnderstanding.understand("Mở YouTube", 2L)
        next.command shouldBe CanonicalCommand.OpenApp("YouTube")
        SessionCommandDecision.lockFinal(2L, next)!!.command shouldBe
            CanonicalCommand.OpenApp("YouTube")
        CanonicalActionGate.tryClaim(1L).shouldBeFalse()
        CanonicalActionGate.tryClaim(2L).shouldBeTrue()
        VoiceSessionManager.terminate(2L, "complete")
        assertTerminalIsolation()
    }

    "new VoiceSession UI clears previous confirmation" {
        val previous = CommandUiState(
            phase = CommandSessionPhase.IDLE_WAKE,
            lastHeard = "Chỉ đường đến sân vận động Mỹ Đình",
            lastReply = "Đang chỉ đường đến sân vận động Mỹ Đình",
            partial = "leftover",
            sessionId = 1L,
        )
        previous.activeResult() shouldBe "Đang chỉ đường đến sân vận động Mỹ Đình"
        val next = previous.forNewSession(2L, CommandSessionPhase.COMMAND_LISTENING)
        next.sessionId shouldBe 2L
        next.lastHeard.shouldBeNull()
        next.lastReply.shouldBeNull()
        next.partial.shouldBeNull()
        next.activeTranscript().shouldBeNull()
        next.activeResult().shouldBeNull()
        next.isLiveSessionUi.shouldBeTrue()
    }

    "live listening hides last action result even if leftover text exists" {
        val contaminated = CommandUiState(
            phase = CommandSessionPhase.COMMAND_LISTENING,
            lastHeard = null,
            lastReply = "Đang mở YouTube…",
            sessionId = 3L,
        )
        contaminated.isLiveSessionUi.shouldBeTrue()
        contaminated.activeResult().shouldBeNull()
        contaminated.activeTranscript().shouldBeNull()
    }
})
