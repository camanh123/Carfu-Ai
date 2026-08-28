package org.stypox.dicio.io.session

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

class CommandSessionMachineTest : StringSpec({
    "idle starts in IDLE_WAKE and is not busy" {
        val m = CommandSessionMachine()
        m.phase shouldBe CommandSessionPhase.IDLE_WAKE
        m.isBusy.shouldBeFalse()
        m.canStartCommandRecognition().shouldBeFalse()
    }

    "overlapping wake detections are ignored" {
        val m = CommandSessionMachine()
        m.onWakeDetected().shouldBeTrue()
        m.sessionId shouldBe 1L
        m.onWakeDetected().shouldBeFalse()
        m.sessionId shouldBe 1L
        m.phase shouldBe CommandSessionPhase.WAKE_DETECTED
    }

    "command recognition is not allowed until TTS onDone" {
        val m = CommandSessionMachine()
        m.onWakeDetected()
        m.canStartCommandRecognition().shouldBeFalse()
        m.onTtsStarted()
        m.phase shouldBe CommandSessionPhase.ACKNOWLEDGING
        m.canStartCommandRecognition().shouldBeFalse()
        m.onTtsCompleted()
        m.canStartCommandRecognition().shouldBeTrue()
        m.onCommandAudioStarted()
        m.phase shouldBe CommandSessionPhase.COMMAND_LISTENING
        m.canStartCommandRecognition().shouldBeFalse()
    }

    "full success path returns to idle so wake can resume" {
        val m = CommandSessionMachine()
        m.onWakeDetected()
        m.onTtsStarted()
        m.onTtsCompleted()
        m.onCommandAudioStarted()
        m.onProcessing()
        m.onResponding()
        m.onReturningToWake()
        m.phase shouldBe CommandSessionPhase.RETURNING_TO_WAKE
        m.onIdle()
        m.phase shouldBe CommandSessionPhase.IDLE_WAKE
        m.isBusy.shouldBeFalse()
        m.onWakeDetected().shouldBeTrue()
    }

    "error and timeout also return to idle" {
        val m = CommandSessionMachine()
        m.onWakeDetected()
        m.onTtsStarted()
        m.onTtsCompleted()
        m.onCommandAudioStarted()
        m.onResponding()
        m.onReturningToWake()
        m.onIdle()
        m.isBusy.shouldBeFalse()
    }

    "reopen after a spoken reply requires TTS completion again" {
        val m = CommandSessionMachine()
        m.onWakeDetected()
        m.onTtsStarted()
        m.onTtsCompleted()
        m.onCommandAudioStarted()
        m.onProcessing()
        m.onTtsStarted()
        m.canStartCommandRecognition().shouldBeFalse()
        m.onTtsCompleted()
        m.canStartCommandRecognition().shouldBeTrue()
        m.onCommandAudioStarted()
        m.phase shouldBe CommandSessionPhase.COMMAND_LISTENING
    }

    "10 command STT starts once after TTS onDone" {
        val m = CommandSessionMachine()
        var starts = 0
        m.onWakeDetected()
        m.onTtsStarted()
        if (m.canStartCommandRecognition()) starts += 1
        m.onTtsCompleted()
        if (m.canStartCommandRecognition()) starts += 1
        m.onCommandAudioStarted()
        if (m.canStartCommandRecognition()) starts += 1
        starts shouldBe 1
        m.phase shouldBe CommandSessionPhase.COMMAND_LISTENING
    }

    "11 wake and command capture are mutually exclusive in the session machine" {
        val m = CommandSessionMachine()
        m.onWakeDetected()
        m.isBusy.shouldBeTrue()
        m.onWakeDetected().shouldBeFalse()
        m.onTtsStarted()
        m.onTtsCompleted()
        m.onCommandAudioStarted()
        m.phase shouldBe CommandSessionPhase.COMMAND_LISTENING
        m.onWakeDetected().shouldBeFalse()
    }
})
