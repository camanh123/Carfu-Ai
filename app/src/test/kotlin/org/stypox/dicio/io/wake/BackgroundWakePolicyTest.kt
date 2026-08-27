package org.stypox.dicio.io.wake

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.stypox.dicio.io.session.CommandCaptureCoordinator
import org.stypox.dicio.io.session.CommandCapturePath
import org.stypox.dicio.io.session.CommandSessionMachine
import org.stypox.dicio.io.session.CommandSessionPhase
import org.stypox.dicio.io.session.Direct16kHzCapture
import org.stypox.dicio.io.session.FallbackCaptureSession
import org.stypox.dicio.io.session.FallbackPcmCapture
import org.stypox.dicio.io.session.FallbackPcmConsumer
import org.stypox.dicio.io.session.VoskRecognizerAdapter
import org.stypox.dicio.settings.datastore.BackgroundWake
import org.stypox.dicio.settings.datastore.UserSettings
import org.vosk.android.RecognitionListener

class BackgroundWakePolicyTest : StringSpec({
    "1 Activity onStop does not stop the wake service" {
        BackgroundWakePolicy.activityOnStopShouldStopWakeService().shouldBeFalse()
        BackgroundWakePolicy.activityOnDestroyShouldStopWakeService().shouldBeFalse()
    }

    "2 Activity destruction does not own or release wake AudioRecord" {
        BackgroundWakePolicy.activityOnDestroyOwnsWakeAudioRecord().shouldBeFalse()
    }

    "3 reopening Activity does not create a duplicate listen loop" {
        BackgroundWakePolicy.skipDuplicateListenLoop(alreadyListening = true).shouldBeTrue()
        BackgroundWakePolicy.skipDuplicateListenLoop(alreadyListening = false).shouldBeFalse()
    }

    "4 service start is idempotent" {
        BackgroundWakePolicy.serviceStartIsIdempotent().shouldBeTrue()
        BackgroundWakePolicy.phaseAfterServiceStart(
            alreadyListening = true,
            currentPhase = CommandSessionPhase.COMMAND_LISTENING,
        ) shouldBe CommandSessionPhase.COMMAND_LISTENING
    }

    "5 START_STICKY recreation restores IDLE_WAKE" {
        BackgroundWakePolicy.phaseAfterServiceStart(
            alreadyListening = false,
            currentPhase = CommandSessionPhase.PROCESSING,
        ) shouldBe CommandSessionPhase.IDLE_WAKE
    }

    "6 boot starts the service only when background wake is enabled" {
        val enabled = UserSettings.getDefaultInstance().toBuilder()
            .setBackgroundWake(BackgroundWake.BACKGROUND_WAKE_ENABLED)
            .build()
        BackgroundWakePolicy.isBackgroundWakeEnabled(enabled).shouldBeTrue()
        BackgroundWakePolicy.shouldStartOnBoot(
            backgroundWakeEnabled = true,
            recordAudioGranted = true,
            wakeDeviceEnabled = true,
            wakeModelReadyOrPending = true,
        ).shouldBeTrue()
        BackgroundWakePolicy.shouldStartOnBoot(
            backgroundWakeEnabled = true,
            recordAudioGranted = false,
            wakeDeviceEnabled = true,
            wakeModelReadyOrPending = true,
        ).shouldBeFalse()
        BackgroundWakePolicy.isBootAction("android.intent.action.BOOT_COMPLETED").shouldBeTrue()
    }

    "7 disabled preference prevents boot start, including UNSET default ON" {
        val unset = UserSettings.getDefaultInstance()
        BackgroundWakePolicy.isBackgroundWakeEnabled(unset).shouldBeTrue()

        val disabled = unset.toBuilder()
            .setBackgroundWake(BackgroundWake.BACKGROUND_WAKE_DISABLED)
            .build()
        BackgroundWakePolicy.isBackgroundWakeEnabled(disabled).shouldBeFalse()
        BackgroundWakePolicy.shouldStartOnBoot(
            backgroundWakeEnabled = false,
            recordAudioGranted = true,
            wakeDeviceEnabled = true,
            wakeModelReadyOrPending = true,
        ).shouldBeFalse()
    }

    "8 screen wake repairs a lost capture without duplication" {
        val healthy = BackgroundWakePolicy.onScreenEvent(
            action = BackgroundWakePolicy.ScreenAction.ON,
            listening = true,
            commandSessionBusy = false,
            recording = true,
            lastSuccessfulReadAgeMs = 200L,
            lastReadFailed = false,
        )
        healthy.openReplacementRecord.shouldBeFalse()
        healthy.wouldDuplicateCapture.shouldBeFalse()

        val lost = BackgroundWakePolicy.onScreenEvent(
            action = BackgroundWakePolicy.ScreenAction.ON,
            listening = true,
            commandSessionBusy = false,
            recording = true,
            lastSuccessfulReadAgeMs = 8_000L,
            lastReadFailed = false,
        )
        lost.releaseCurrentRecord.shouldBeTrue()
        lost.openReplacementRecord.shouldBeTrue()
        lost.wouldDuplicateCapture.shouldBeFalse()

        val busy = BackgroundWakePolicy.onScreenEvent(
            action = BackgroundWakePolicy.ScreenAction.USER_PRESENT,
            listening = true,
            commandSessionBusy = true,
            recording = false,
            lastSuccessfulReadAgeMs = 8_000L,
            lastReadFailed = false,
        )
        busy.openReplacementRecord.shouldBeFalse()

        BackgroundWakePolicy.shouldRecreateAfterReadError(-3).shouldBeTrue()
        BackgroundWakePolicy.shouldRecreateAfterReadError(1280).shouldBeFalse()
        BackgroundWakePolicy.nextOpenRetryDelayMs(250L) shouldBe 500L
        BackgroundWakePolicy.nextOpenRetryDelayMs(10_000L) shouldBe
            BackgroundWakePolicy.MAX_OPEN_RETRY_MS
    }

    "9 explicit Stop releases microphone and service resources" {
        val stop = BackgroundWakePolicy.userDisableBackgroundWake()
        stop.persistDisabled.shouldBeTrue()
        stop.releaseAudioRecord.shouldBeTrue()
        stop.abandonAudioFocus.shouldBeTrue()
        stop.preventBootStart.shouldBeTrue()
        stop.stopService.shouldBeTrue()
    }

    "10 wake to command to response to wake works with Activity absent" {
        BackgroundWakePolicy.wakeCommandCycleRequiresActivity().shouldBeFalse()
        val m = CommandSessionMachine()
        m.onWakeDetected().shouldBeTrue()
        m.onTtsStarted()
        m.onTtsCompleted()
        m.onCommandAudioStarted()
        m.phase shouldBe CommandSessionPhase.COMMAND_LISTENING
        m.onProcessing()
        m.onResponding()
        m.onReturningToWake()
        m.onIdle()
        m.phase shouldBe CommandSessionPhase.IDLE_WAKE
        m.isBusy.shouldBeFalse()
        m.onWakeDetected().shouldBeTrue()
    }

    "11 Path A and Path B command capture remain mutually exclusive" {
        val direct = ExclusiveDirectCapture()
        val fallback = ExclusiveFallbackCapture()
        val coordinator = CommandCaptureCoordinator(
            direct = direct,
            fallback = fallback,
            recognizer = object : VoskRecognizerAdapter {
                override fun acceptWaveForm(samples: ShortArray, length: Int) = false
                override fun resultJson() = "{}"
                override fun partialJson() = "{}"
                override fun finalJson() = "{}"
            },
        )
        coordinator.start(NoopListener())
        coordinator.path shouldBe CommandCapturePath.DIRECT
        coordinator.bothPathsRunning().shouldBeFalse()
        BackgroundWakePolicy.commandCapturePathsAreMutuallyExclusive(
            directRunning = coordinator.isDirectRunning(),
            fallbackRunning = coordinator.isFallbackRunning(),
        ).shouldBeTrue()

        val fallbackOnly = CommandCaptureCoordinator(
            direct = ExclusiveDirectCapture(available = false),
            fallback = ExclusiveFallbackCapture(),
            recognizer = object : VoskRecognizerAdapter {
                override fun acceptWaveForm(samples: ShortArray, length: Int) = false
                override fun resultJson() = "{}"
                override fun partialJson() = "{}"
                override fun finalJson() = "{}"
            },
        )
        fallbackOnly.start(NoopListener())
        fallbackOnly.path shouldBe CommandCapturePath.FALLBACK
        fallbackOnly.bothPathsRunning().shouldBeFalse()
        fallbackOnly.stop()
        coordinator.stop()
    }

    "notification states never include transcripts" {
        BackgroundWakePolicy.notificationKind(
            recordAudioGranted = true,
            backgroundWakeEnabled = true,
            wakeDeviceEnabled = true,
            phase = CommandSessionPhase.IDLE_WAKE,
        ) shouldBe WakeNotificationKind.WAITING_WAKE
        BackgroundWakePolicy.notificationKind(
            recordAudioGranted = true,
            backgroundWakeEnabled = true,
            wakeDeviceEnabled = true,
            phase = CommandSessionPhase.COMMAND_LISTENING,
        ) shouldBe WakeNotificationKind.LISTENING_COMMAND
        BackgroundWakePolicy.notificationKind(
            recordAudioGranted = true,
            backgroundWakeEnabled = true,
            wakeDeviceEnabled = true,
            phase = CommandSessionPhase.PROCESSING,
        ) shouldBe WakeNotificationKind.PROCESSING
        BackgroundWakePolicy.notificationKind(
            recordAudioGranted = false,
            backgroundWakeEnabled = true,
            wakeDeviceEnabled = true,
            phase = CommandSessionPhase.IDLE_WAKE,
        ) shouldBe WakeNotificationKind.NEED_PERMISSION
        BackgroundWakePolicy.notificationKind(
            recordAudioGranted = true,
            backgroundWakeEnabled = false,
            wakeDeviceEnabled = true,
            phase = CommandSessionPhase.IDLE_WAKE,
        ) shouldBe WakeNotificationKind.MIC_OFF
    }
})

private class NoopListener : RecognitionListener {
    override fun onPartialResult(hypothesis: String) = Unit
    override fun onResult(hypothesis: String) = Unit
    override fun onFinalResult(hypothesis: String) = Unit
    override fun onError(exception: Exception) = Unit
    override fun onTimeout() = Unit
}

private class ExclusiveDirectCapture(
    var available: Boolean = true,
) : Direct16kHzCapture {
    private var running = false
    override fun isAvailable(): Boolean = available
    override fun start(listener: RecognitionListener): Boolean {
        if (!available) return false
        running = true
        return true
    }
    override fun stop() { running = false }
    override fun shutdown() { running = false }
    override fun isRunning(): Boolean = running
}

private class ExclusiveFallbackCapture : FallbackPcmCapture {
    override fun open(rates: IntArray): FallbackCaptureSession = object : FallbackCaptureSession {
        override val rateHz: Int = 48_000
        override val bufferBytes: Int = 3840
        override val suggestedReadShorts: Int = 1920
        override fun start(consumer: FallbackPcmConsumer) = Unit
        override fun stopAndRelease() = Unit
    }
}
