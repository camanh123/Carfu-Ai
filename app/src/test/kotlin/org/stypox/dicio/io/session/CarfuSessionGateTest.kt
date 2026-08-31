package org.stypox.dicio.io.session

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.stypox.dicio.io.input.vosk.VoskModelInventory
import org.stypox.dicio.io.input.vosk.VoskModelLifecycle

private class GateClock {
    var now: Long = 1_000_000L
    fun advance(by: Long) {
        now += by
    }
}

class CarfuSessionGateTest : StringSpec({
    val clock = GateClock()

    beforeTest {
        CarfuSessionGate.resetForTests()
        CarfuActivationSource.resetForTests()
        clock.now = 1_000_000L
        CarfuSessionGate.nowMs = { clock.now }
    }

    fun startWithId(id: Long): () -> Long = { id }

    "one MODE intent fan-out still creates exactly one session" {
        val first = CarfuSessionGate.requestStart(
            origin = CarfuSessionGate.Origin.HARDWARE_BUTTON,
            phase = CommandSessionPhase.IDLE_WAKE,
            intentAction = "android.intent.action.ASSIST",
            intentComponent = "org.stypox.dicio/.MainActivity",
            startSession = startWithId(1L),
        )
        first.accepted.shouldBeTrue()
        first.sessionId shouldBe 1L

        val vis = CarfuSessionGate.requestStart(
            origin = CarfuSessionGate.Origin.HARDWARE_BUTTON,
            phase = CommandSessionPhase.WAKE_DETECTED,
            intentAction = "android.intent.action.VOICE_COMMAND",
            startSession = startWithId(2L),
        )
        vis.accepted.shouldBeFalse()
        vis.decision shouldBe CarfuSessionGate.Decision.REJECTED_BUSY

        val webSearch = CarfuSessionGate.requestStart(
            origin = CarfuSessionGate.Origin.HARDWARE_BUTTON,
            phase = CommandSessionPhase.ACKNOWLEDGING,
            intentAction = "android.speech.action.WEB_SEARCH",
            startSession = startWithId(3L),
        )
        webSearch.accepted.shouldBeFalse()
        CarfuSessionGate.lastAcceptedSessionId shouldBe 1L
        CarfuSessionGate.activeSessionId shouldBe 1L
    }

    "duplicate assist callbacks are rejected by debounce after the session ends" {
        CarfuSessionGate.requestStart(
            origin = CarfuSessionGate.Origin.HARDWARE_BUTTON,
            phase = CommandSessionPhase.IDLE_WAKE,
            intentAction = "android.intent.action.ASSIST",
            startSession = startWithId(1L),
        )
        CarfuSessionGate.onSessionFinished(
            sessionId = 1L,
            hadTranscript = true,
            origin = CarfuSessionGate.Origin.HARDWARE_BUTTON,
        )
        val duplicate = CarfuSessionGate.requestStart(
            origin = CarfuSessionGate.Origin.HARDWARE_BUTTON,
            phase = CommandSessionPhase.IDLE_WAKE,
            intentAction = "android.intent.action.VOICE_ASSIST",
            startSession = startWithId(2L),
        )
        duplicate.accepted.shouldBeFalse()
        duplicate.decision shouldBe CarfuSessionGate.Decision.REJECTED_DEBOUNCE

        clock.advance(CarfuSessionGate.ASSIST_DEBOUNCE_MS)
        val later = CarfuSessionGate.requestStart(
            origin = CarfuSessionGate.Origin.HARDWARE_BUTTON,
            phase = CommandSessionPhase.IDLE_WAKE,
            intentAction = "android.intent.action.ASSIST",
            startSession = startWithId(3L),
        )
        later.accepted.shouldBeTrue()
        later.sessionId shouldBe 3L
        later.sessionId.shouldBeGreaterThan(1L)
    }

    "background wake OFF cancels pending automatic sessions" {
        CarfuSessionGate.requestStart(
            origin = CarfuSessionGate.Origin.WAKE_WORD,
            phase = CommandSessionPhase.IDLE_WAKE,
            startSession = startWithId(11L),
        )
        CarfuSessionGate.isCurrent(11L).shouldBeTrue()
        CarfuSessionGate.setBackgroundWakeEnabled(false)
        CarfuSessionGate.cancel("background_wake_disabled") shouldBe 11L
        CarfuSessionGate.isCurrent(11L).shouldBeFalse()
        CarfuSessionGate.activeSessionId shouldBe 0L

        val auto = CarfuSessionGate.requestStart(
            origin = CarfuSessionGate.Origin.WAKE_WORD,
            phase = CommandSessionPhase.IDLE_WAKE,
            startSession = startWithId(12L),
        )
        auto.accepted.shouldBeFalse()
        auto.decision shouldBe CarfuSessionGate.Decision.REJECTED_WAKE_OFF
    }

    "RETURNING_TO_WAKE cannot restart while preference is OFF" {
        CarfuSessionGate.setBackgroundWakeEnabled(false)
        CarfuSessionGate.returningToWakeMayRestartListening().shouldBeFalse()
        CarfuPcmRouter.route(
            phase = CommandSessionPhase.RETURNING_TO_WAKE,
            backgroundWakeEnabled = false,
        ) shouldBe CarfuPcmRoute.DISCARD
        CarfuPcmRouter.route(
            phase = CommandSessionPhase.IDLE_WAKE,
            backgroundWakeEnabled = false,
        ) shouldBe CarfuPcmRoute.DISCARD
    }

    "manual MODE works once while background wake is OFF" {
        CarfuSessionGate.setBackgroundWakeEnabled(false)
        val mode = CarfuSessionGate.requestStart(
            origin = CarfuSessionGate.Origin.HARDWARE_BUTTON,
            phase = CommandSessionPhase.IDLE_WAKE,
            intentAction = "android.intent.action.ASSIST",
            startSession = startWithId(21L),
        )
        mode.accepted.shouldBeTrue()
        CarfuSessionGate.onSessionFinished(
            sessionId = 21L,
            hadTranscript = true,
            origin = CarfuSessionGate.Origin.HARDWARE_BUTTON,
        )
        CarfuSessionGate.activeSessionId shouldBe 0L
        CarfuSessionGate.returningToWakeMayRestartListening().shouldBeFalse()
    }

    "missing model cannot enter COMMAND_LISTENING" {
        VoskModelInventory.canEnterCommandListening(VoskModelLifecycle.MISSING).shouldBeFalse()
        VoskModelInventory.canEnterCommandListening(VoskModelLifecycle.DOWNLOADING).shouldBeFalse()
        VoskModelInventory.canEnterCommandListening(VoskModelLifecycle.LOADING).shouldBeFalse()
        VoskModelInventory.canEnterCommandListening(VoskModelLifecycle.READY).shouldBeTrue()

        val rejected = CarfuSessionGate.requestStart(
            origin = CarfuSessionGate.Origin.WAKE_WORD,
            phase = CommandSessionPhase.IDLE_WAKE,
            modelReady = false,
            startSession = startWithId(31L),
        )
        rejected.accepted.shouldBeFalse()
        rejected.decision shouldBe CarfuSessionGate.Decision.REJECTED_MODEL_NOT_READY
    }

    "automatic empty session is silent and cooled down" {
        CarfuActivationSource.markAutomaticWake()
        CarfuActivationSource.shouldSpeakUnclear().shouldBeFalse()
        CarfuSessionGate.requestStart(
            origin = CarfuSessionGate.Origin.WAKE_WORD,
            phase = CommandSessionPhase.IDLE_WAKE,
            startSession = startWithId(41L),
        )
        CarfuSessionGate.onSessionFinished(
            sessionId = 41L,
            hadTranscript = false,
            origin = CarfuSessionGate.Origin.WAKE_WORD,
        )
        val restart = CarfuSessionGate.requestStart(
            origin = CarfuSessionGate.Origin.WAKE_WORD,
            phase = CommandSessionPhase.IDLE_WAKE,
            startSession = startWithId(42L),
        )
        restart.accepted.shouldBeFalse()
        restart.decision shouldBe CarfuSessionGate.Decision.REJECTED_EMPTY_COOLDOWN
    }

    "manual empty session responds at most once and does not restart immediately" {
        CarfuActivationSource.markHardwareButton()
        CarfuActivationSource.shouldSpeakUnclear().shouldBeTrue()
        CarfuActivationSource.shouldSpeakUnclear().shouldBeFalse()
        CarfuSessionGate.requestStart(
            origin = CarfuSessionGate.Origin.HARDWARE_BUTTON,
            phase = CommandSessionPhase.IDLE_WAKE,
            startSession = startWithId(51L),
        )
        CarfuSessionGate.onSessionFinished(
            sessionId = 51L,
            hadTranscript = false,
            origin = CarfuSessionGate.Origin.HARDWARE_BUTTON,
        )
        val again = CarfuSessionGate.requestStart(
            origin = CarfuSessionGate.Origin.HARDWARE_BUTTON,
            phase = CommandSessionPhase.IDLE_WAKE,
            startSession = startWithId(52L),
        )
        again.accepted.shouldBeFalse()
        again.decision shouldBe CarfuSessionGate.Decision.REJECTED_DEBOUNCE
        clock.advance(CarfuSessionGate.ASSIST_DEBOUNCE_MS)
        val stillCooling = CarfuSessionGate.requestStart(
            origin = CarfuSessionGate.Origin.HARDWARE_BUTTON,
            phase = CommandSessionPhase.IDLE_WAKE,
            startSession = startWithId(53L),
        )
        stillCooling.accepted.shouldBeFalse()
        stillCooling.decision shouldBe CarfuSessionGate.Decision.REJECTED_EMPTY_COOLDOWN
    }

    "stale TTS onDone is ignored after cancel" {
        CarfuSessionGate.requestStart(
            origin = CarfuSessionGate.Origin.WAKE_WORD,
            phase = CommandSessionPhase.IDLE_WAKE,
            startSession = startWithId(61L),
        )
        CarfuSessionGate.cancel("background_wake_disabled")
        CarfuSessionGate.isCurrent(61L).shouldBeFalse()
    }

    "service restart never starts a command session" {
        val result = CarfuSessionGate.requestStart(
            origin = CarfuSessionGate.Origin.SERVICE_RESTART,
            phase = CommandSessionPhase.IDLE_WAKE,
            intentAction = "android.intent.action.MAIN",
            startSession = startWithId(99L),
        )
        result.accepted.shouldBeFalse()
        result.decision shouldBe CarfuSessionGate.Decision.REJECTED_SERVICE_RESTART
        CarfuSessionGate.activeSessionId shouldBe 0L
    }

    "UI origin is allowed while background wake is OFF" {
        CarfuSessionGate.setBackgroundWakeEnabled(false)
        val ui = CarfuSessionGate.requestStart(
            origin = CarfuSessionGate.Origin.UI,
            phase = CommandSessionPhase.IDLE_WAKE,
            startSession = startWithId(71L),
        )
        ui.accepted.shouldBeTrue()
    }
})
