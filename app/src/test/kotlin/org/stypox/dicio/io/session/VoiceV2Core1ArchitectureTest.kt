package org.stypox.dicio.io.session

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * V2-CORE-1 architecture proofs (updated for Kiki-aligned MODE behavior):
 * no spoken ACK, ~5s silent exit, live transcript, online-first gate.
 */
class VoiceV2Core1ArchitectureTest : StringSpec({
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
        CommandSessionOutcome.resetForTests()
        CarfuActivationSource.resetForTests()
        clock.now = 1_000_000L
        VoiceTriggerManager.nowMs = { clock.now }
        VoiceSessionManager.nowMs = { clock.now }
        CarfuSessionGate.nowMs = { clock.now }
        CarfuSessionGate.setBackgroundWakeEnabled(true)
        VoiceOnlinePolicy.onlineOverride = true
    }

    fun acceptMode(
        origin: VoiceTriggerManager.Origin = VoiceTriggerManager.Origin.HARDWARE_MODE,
        sessionId: Long = 1L,
    ): VoiceSessionManager.Session {
        val trigger = VoiceTriggerManager.request(origin)
        trigger.accepted.shouldBeTrue()
        trigger.trigger.shouldNotBeNull()
        val session = VoiceSessionManager.createFromTrigger(trigger.trigger!!, sessionId)
        session.shouldNotBeNull()
        return session!!
    }

    "1. background ON + no MODE → zero command session" {
        CarfuSessionGate.setBackgroundWakeEnabled(true)
        VoiceSessionManager.hasLiveSession().shouldBeFalse()
        VoiceSessionManager.liveSession().shouldBeNull()
        VoiceTriggerManager.hasOpenTrigger().shouldBeFalse()
    }

    "2. OpenWakeWord ACCEPT → zero command session in V2-CORE-1" {
        val result = VoiceTriggerManager.request(
            origin = VoiceTriggerManager.Origin.WAKE_WORD,
            reason = "oww_accept",
        )
        result.accepted.shouldBeFalse()
        result.decision shouldBe VoiceTriggerManager.Decision.REJECTED_PASSIVE
        VoiceSessionManager.hasLiveSession().shouldBeFalse()
    }

    "3. background alive >15s → zero ACK → zero command SR" {
        CarfuSessionGate.setBackgroundWakeEnabled(true)
        VoiceTriggerManager.request(VoiceTriggerManager.Origin.WAKE_WORD)
        clock.advance(16_000L)
        VoiceTriggerManager.request(VoiceTriggerManager.Origin.TIMEOUT, reason = "sim_timeout")
        VoiceSessionManager.counters().ackRequests shouldBe 0
        VoiceSessionManager.counters().listenStarts shouldBe 0
        VoiceSessionManager.hasLiveSession().shouldBeFalse()
    }

    "4. service restart → passive IDLE" {
        val result = VoiceTriggerManager.request(
            origin = VoiceTriggerManager.Origin.SERVICE_RESTART,
            reason = "start_sticky",
        )
        result.accepted.shouldBeFalse()
        result.decision shouldBe VoiceTriggerManager.Decision.REJECTED_PASSIVE
        VoiceSessionManager.state() shouldBe VoiceSessionManager.State.IDLE
    }

    "5. stale Activity/Assist delivery → no new session" {
        val stale = VoiceTriggerManager.request(
            origin = VoiceTriggerManager.Origin.STALE_ASSIST,
            staleAssist = true,
            reason = "activity_recreate",
        )
        stale.accepted.shouldBeFalse()
        stale.decision shouldBe VoiceTriggerManager.Decision.REJECTED_STALE
        VoiceSessionManager.hasLiveSession().shouldBeFalse()
    }

    "6. fresh MODE → one trigger → one session" {
        val session = acceptMode(sessionId = 42L)
        session.sessionId shouldBe 42L
        session.triggerId shouldBe 1L
        VoiceTriggerManager.sessionForTrigger(1L) shouldBe 42L
        VoiceSessionManager.hasLiveSession().shouldBeTrue()
        VoiceSessionManager.modeUsesSpokenAck().shouldBeFalse()
    }

    "7. duplicate same trigger / second MODE while busy → one session only" {
        acceptMode(sessionId = 7L)
        val dup = VoiceTriggerManager.request(VoiceTriggerManager.Origin.HARDWARE_MODE)
        dup.accepted.shouldBeFalse()
        dup.decision shouldBe VoiceTriggerManager.Decision.REJECTED_BUSY
        VoiceSessionManager.liveSession()!!.sessionId shouldBe 7L
    }

    "8. MODE session → zero spoken ACK" {
        val session = acceptMode(sessionId = 8L)
        VoiceSessionManager.requestAck(session.sessionId).shouldBeFalse()
        VoiceSessionManager.counters().ackRequests shouldBe 0
        VoiceSessionManager.counters().ackStarts shouldBe 0
        KnownGoodListenerInvariants.MAX_ACK_PER_MODE shouldBe 0
        KnownGoodListenerInvariants.modeUsesSpokenAck().shouldBeFalse()
    }

    "9. one session → max one logical listen" {
        val session = acceptMode(sessionId = 9L)
        VoiceSessionManager.requestListen(session.sessionId).shouldBeTrue()
        VoiceSessionManager.requestListen(session.sessionId).shouldBeFalse()
        VoiceSessionManager.counters().listenStarts shouldBe 1
    }

    "10. STT failure → terminal → IDLE → no automatic new session" {
        val session = acceptMode(sessionId = 10L)
        VoiceSessionManager.requestListen(session.sessionId)
        VoiceSessionManager.onListenTerminal(session.sessionId, "sr_error").shouldBeTrue()
        VoiceSessionManager.terminate(session.sessionId, "sr_error").shouldBeTrue()
        VoiceSessionManager.state() shouldBe VoiceSessionManager.State.IDLE
        clock.advance(1_000L)
        VoiceSessionManager.hasLiveSession().shouldBeFalse()
    }

    "11. 15+ simulated seconds after terminal → still IDLE" {
        val session = acceptMode(sessionId = 11L)
        VoiceSessionManager.terminate(session.sessionId, "done")
        clock.advance(16_000L)
        VoiceSessionManager.state() shouldBe VoiceSessionManager.State.IDLE
        KnownGoodListenerInvariants.automaticRestartAfterSessionEndMs(16_000L).shouldBeFalse()
    }

    "12. UI MODE and hardware MODE converge through same trigger owner" {
        VoiceTriggerManager.isAuthorized(VoiceTriggerManager.Origin.HARDWARE_MODE).shouldBeTrue()
        VoiceTriggerManager.isAuthorized(VoiceTriggerManager.Origin.UI_MODE).shouldBeTrue()
        val hw = VoiceTriggerManager.request(VoiceTriggerManager.Origin.HARDWARE_MODE)
        hw.accepted.shouldBeTrue()
        VoiceTriggerManager.abandonOpenTrigger(hw.trigger!!.triggerId, "test_switch")
        clock.advance(VoiceTriggerManager.HARDWARE_DEBOUNCE_MS)
        val ui = VoiceTriggerManager.request(VoiceTriggerManager.Origin.UI_MODE)
        ui.accepted.shouldBeTrue()
        ui.trigger!!.origin shouldBe VoiceTriggerManager.Origin.UI_MODE
    }

    "13. one command outcome → max one execution" {
        val session = acceptMode(sessionId = 13L)
        VoiceSessionManager.requestListen(session.sessionId)
        VoiceSessionManager.onUnderstandingStart(session.sessionId)
        VoiceSessionManager.requestExecution(session.sessionId).shouldBeTrue()
        VoiceSessionManager.requestExecution(session.sessionId).shouldBeFalse()
        VoiceSessionManager.counters().executionStarts shouldBe 1
    }

    "14. stale SpeechRecognizer callback after terminal → ignored" {
        val session = acceptMode(sessionId = 14L)
        val sid = session.sessionId
        VoiceSessionManager.terminate(sid, "done")
        VoiceSessionManager.shouldIgnoreCallback(sid).shouldBeTrue()
        VoiceSessionManager.requestListen(sid).shouldBeFalse()
        VoiceSessionManager.requestExecution(sid).shouldBeFalse()
    }

    "15. CommandUnderstanding can represent NAVIGATE(destination arbitrary string)" {
        val u = CommandUnderstanding.navigate(
            raw = "Đưa tôi đến Mỹ Đình",
            normalized = "dua toi den my dinh",
            destination = "Mỹ Đình",
            confidence = 0.91f,
        )
        u.intent shouldBe VoiceIntent.NAVIGATE
        u.destination shouldBe "Mỹ Đình"
        u.confirmationSpeechVi() shouldBe "Đang chỉ đường đến Mỹ Đình"
        u.executionPayload()[CommandEntityKeys.DESTINATION] shouldBe "Mỹ Đình"
    }

    "16. CommandUnderstanding can represent PLAY_MEDIA(query, provider)" {
        val u = CommandUnderstanding.playMedia(
            raw = "Mở bài Đừng xa em đêm nay trên YouTube",
            normalized = "mo bai dung xa em dem nay tren youtube",
            query = "Đừng xa em đêm nay",
            provider = "YouTube",
            confidence = 0.88f,
        )
        u.intent shouldBe VoiceIntent.PLAY_MEDIA
        u.confirmationSpeechVi() shouldBe "Đang mở Đừng xa em đêm nay trên YouTube"
    }

    "no-speech ~5s silent exit → IDLE without unclear TTS" {
        val session = acceptMode(sessionId = 50L)
        VoiceSessionManager.requestListen(session.sessionId).shouldBeTrue()
        VoiceSessionManager.shouldSilentExit(session.sessionId).shouldBeFalse()
        clock.advance(VoiceSessionManager.NO_SPEECH_TIMEOUT_MS)
        VoiceSessionManager.shouldSilentExit(session.sessionId).shouldBeTrue()
        VoiceSessionManager.shouldSpeakNoSpeechPrompt().shouldBeFalse()
        KnownGoodListenerInvariants.noSpeechExitsSilently().shouldBeTrue()
        VoiceSessionManager.terminate(session.sessionId, "product_silence_5s")
        VoiceSessionManager.state() shouldBe VoiceSessionManager.State.IDLE

        clock.advance(VoiceTriggerManager.HARDWARE_DEBOUNCE_MS)
        val s2 = acceptMode(sessionId = 51L)
        VoiceSessionManager.requestListen(s2.sessionId)
        VoiceSessionManager.onLiveTranscript(s2.sessionId, "mấy giờ")
        clock.advance(VoiceSessionManager.NO_SPEECH_TIMEOUT_MS)
        VoiceSessionManager.shouldSilentExit(s2.sessionId).shouldBeFalse()
    }

    "live transcript path updates raw transcript distinctly from understanding" {
        val session = acceptMode(sessionId = 60L)
        VoiceSessionManager.requestListen(session.sessionId)
        VoiceSessionManager.onLiveTranscript(session.sessionId, "Chỉ đường đến Mỹ Đình")
        VoiceSessionManager.rawLiveTranscript() shouldBe "Chỉ đường đến Mỹ Đình"
        VoiceSessionManager.speechDetected().shouldBeTrue()
        val understood = CommandUnderstanding.navigate(
            raw = VoiceSessionManager.rawLiveTranscript()!!,
            normalized = "chi duong den my dinh",
            destination = "Mỹ Đình",
        )
        understood.rawTranscript shouldBe "Chỉ đường đến Mỹ Đình"
        understood.normalizedTranscript shouldContain "my dinh"
        understood.confirmationSpeechVi().shouldContain("Mỹ Đình")
    }

    "internet unavailable blocks online voice session entry" {
        VoiceOnlinePolicy.onlineOverride = false
        VoiceOnlinePolicy.mayEnterOnlineVoiceSession(false).shouldBeFalse()
        VoiceOnlinePolicy.OFFLINE_TTS_VI shouldContain "Internet"
        // Recovery alone is not a trigger
        VoiceOnlinePolicy.onlineOverride = true
        VoiceTriggerManager.hasOpenTrigger().shouldBeFalse()
        VoiceSessionManager.hasLiveSession().shouldBeFalse()
    }

    "authorized triggers only HARDWARE_MODE and UI_MODE" {
        VoiceTriggerManager.Origin.entries.forEach { origin ->
            val authorized = VoiceTriggerManager.isAuthorized(origin)
            when (origin) {
                VoiceTriggerManager.Origin.HARDWARE_MODE,
                VoiceTriggerManager.Origin.UI_MODE,
                -> authorized.shouldBeTrue()
                else -> authorized.shouldBeFalse()
            }
        }
    }

    "MODE-to-listening skips ACKNOWLEDGING" {
        val session = acceptMode(sessionId = 70L)
        VoiceSessionManager.state() shouldBe VoiceSessionManager.State.LISTENING
        VoiceSessionManager.requestListen(session.sessionId)
        VoiceSessionManager.state() shouldBe VoiceSessionManager.State.LISTENING
        VoiceSessionManager.counters().ackRequests shouldBe 0
    }

    "MAX_SR_REARMS remains 0 under V2 spine" {
        org.stypox.dicio.io.input.CommandRecognitionPolicy.MAX_SR_REARMS shouldBe 0
        KnownGoodListenerInvariants.startListeningCountForModePress(3) shouldBe 1
        KnownGoodListenerInvariants.PRODUCT_NO_SPEECH_TIMEOUT_MS shouldBe 5_000L
    }

    "MODE while LISTENING cancels after fan-out window; immediate re-MODE accepted" {
        val session = acceptMode(sessionId = 80L)
        VoiceSessionManager.requestListen(session.sessionId).shouldBeTrue()
        // Assist fan-out within 400ms must not cancel.
        clock.advance(100L)
        val fanOut = VoiceTriggerManager.request(VoiceTriggerManager.Origin.HARDWARE_MODE)
        fanOut.accepted.shouldBeFalse()
        fanOut.decision shouldBe VoiceTriggerManager.Decision.REJECTED_BUSY
        VoiceSessionManager.hasLiveSession().shouldBeTrue()

        clock.advance(VoiceSessionManager.TOGGLE_CANCEL_MIN_AGE_MS)
        val cancel = VoiceTriggerManager.request(VoiceTriggerManager.Origin.HARDWARE_MODE)
        cancel.accepted.shouldBeFalse()
        cancel.decision shouldBe VoiceTriggerManager.Decision.CANCEL_CURRENT
        VoiceSessionManager.shouldToggleCancelOnMode().shouldBeTrue()

        VoiceSessionManager.terminate(session.sessionId, "mode_toggle_cancel")
        VoiceTriggerManager.onSessionTerminal(session.sessionId)
        VoiceSessionManager.hasLiveSession().shouldBeFalse()

        // No manual cooldown — next MODE starts immediately.
        val again = acceptMode(sessionId = 81L)
        VoiceSessionManager.requestListen(again.sessionId).shouldBeTrue()
        VoiceSessionManager.state() shouldBe VoiceSessionManager.State.LISTENING
    }

    "UI_MODE toggle cancel mirrors HARDWARE_MODE" {
        val session = acceptMode(
            origin = VoiceTriggerManager.Origin.UI_MODE,
            sessionId = 90L,
        )
        VoiceSessionManager.requestListen(session.sessionId)
        clock.advance(VoiceSessionManager.TOGGLE_CANCEL_MIN_AGE_MS + 1L)
        val cancel = VoiceTriggerManager.request(VoiceTriggerManager.Origin.UI_MODE)
        cancel.decision shouldBe VoiceTriggerManager.Decision.CANCEL_CURRENT
    }
})
