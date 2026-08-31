package org.stypox.dicio.io.session

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.vosk.android.RecognitionListener
import java.io.IOException

private open class FakeRecognitionListener : RecognitionListener {
    val partials = mutableListOf<String>()
    val results = mutableListOf<String>()
    val errors = mutableListOf<Exception>()
    var timeouts = 0

    override fun onPartialResult(hypothesis: String) {
        partials.add(hypothesis)
    }

    override fun onResult(hypothesis: String) {
        results.add(hypothesis)
    }

    override fun onFinalResult(hypothesis: String) = Unit

    override fun onError(exception: Exception) {
        errors.add(exception)
    }

    override fun onTimeout() {
        timeouts += 1
    }
}

private class FakeDirectCapture(
    var available: Boolean = true,
    var startSucceeds: Boolean = true,
) : Direct16kHzCapture {
    var startCount = 0
    var stopCount = 0
    var shutdownCount = 0
    var consumer: FallbackPcmConsumer? = null
    private var running = false

    override fun isAvailable(): Boolean = available

    override fun start(consumer: FallbackPcmConsumer): Boolean {
        startCount += 1
        if (!available || !startSucceeds) return false
        this.consumer = consumer
        running = true
        return true
    }

    override fun stop() {
        stopCount += 1
        running = false
        consumer = null
    }

    override fun shutdown() {
        shutdownCount += 1
        running = false
        available = false
        consumer = null
    }

    override fun isRunning(): Boolean = running

    fun emit(samples: ShortArray) {
        consumer?.onPcm(samples, samples.size)
    }
}

private class FakeFallbackSession(
    override val rateHz: Int,
    override val bufferBytes: Int,
) : FallbackCaptureSession {
    override val suggestedReadShorts: Int = (bufferBytes / 2).coerceAtLeast(160)
    var started = false
    var released = false
    var workerStopped = false
    var consumer: FallbackPcmConsumer? = null

    override fun start(consumer: FallbackPcmConsumer) {
        started = true
        this.consumer = consumer
    }

    override fun stopAndRelease() {
        released = true
        workerStopped = true
        started = false
        consumer = null
    }

    fun emit(samples: ShortArray) {
        consumer?.onPcm(samples, samples.size)
    }

    fun emitError(error: Exception) {
        consumer?.onReadError(error)
    }
}

private class FakeFallbackPcmCapture(
    private val probe: CaptureRateProbe,
    private val failOpenRates: Set<Int> = emptySet(),
) : FallbackPcmCapture {
    val openAttempts = mutableListOf<Int>()
    val seenRateOrder = mutableListOf<IntArray>()
    var session: FakeFallbackSession? = null
    var openCount = 0

    override fun open(rates: IntArray): FallbackCaptureSession? {
        seenRateOrder.add(rates.copyOf())
        return AudioCaptureConfig.openFirstFallback(rates, probe) { rateHz, bufferBytes ->
            openAttempts.add(rateHz)
            if (rateHz in failOpenRates) {
                null
            } else {
                openCount += 1
                FakeFallbackSession(rateHz, bufferBytes).also { session = it }
            }
        }
    }
}

private class FakeRecognizerAdapter : VoskRecognizerAdapter {
    val accepted = mutableListOf<ShortArray>()
    var nextIsFinal = false
    var result = """{"text":"mo youtube"}"""
    var partial = """{"partial":"mo"}"""

    override fun acceptWaveForm(samples: ShortArray, length: Int): Boolean {
        accepted.add(samples.copyOf(length))
        return nextIsFinal
    }

    override fun resultJson(): String = result

    override fun partialJson(): String = partial

    override fun finalJson(): String = """{"text":""}"""
}

private class ManualTimeoutScheduler : CaptureTimeoutScheduler {
    var pending: (() -> Unit)? = null

    override fun schedule(delayMs: Long, onTimeout: () -> Unit): CaptureTimeoutScheduler.Handle {
        pending = onTimeout
        return CaptureTimeoutScheduler.Handle { pending = null }
    }

    fun fire() {
        val action = pending
        pending = null
        action?.invoke()
    }
}

private class FakeWakeResume {
    var resumed = false
    fun resume() {
        resumed = true
    }
}

private fun endCommandSession(machine: CommandSessionMachine, wake: FakeWakeResume) {
    machine.onReturningToWake()
    machine.onIdle()
    wake.resume()
}

private fun listeningSession(): CommandSessionMachine {
    val machine = CommandSessionMachine()
    machine.onWakeDetected()
    machine.onTtsStarted()
    machine.onTtsCompleted()
    machine.onCommandAudioStarted()
    return machine
}

class CommandCaptureProductionPathTest : StringSpec({
    beforeTest {
        CarfuPcmHub.resetForTests()
    }

    "native 16 kHz selects the shared PCM hub path without SpeechService" {
        val direct = FakeDirectCapture(available = true, startSucceeds = true)
        val fallback = FakeFallbackPcmCapture(probe = { 4096 })
        val recognizer = FakeRecognizerAdapter()
        val coordinator = CommandCaptureCoordinator(direct, fallback, recognizer)
        val result = coordinator.start(FakeRecognitionListener())

        result shouldBe CommandCaptureStartResult.Direct
        coordinator.path shouldBe CommandCapturePath.DIRECT
        direct.startCount shouldBe 1
        direct.isRunning().shouldBeTrue()
        fallback.openCount shouldBe 0
        fallback.session.shouldBeNull()
        coordinator.isFallbackRunning().shouldBeFalse()
        coordinator.bothPathsRunning().shouldBeFalse()
        coordinator.resampleInvocations shouldBe 0
        coordinator.recognizerAccepts shouldBe 0
    }

    "failure to initialize or start native 16 kHz selects fallback capture" {
        val direct = FakeDirectCapture(available = true, startSucceeds = false)
        val fallback = FakeFallbackPcmCapture(
            probe = { rate -> if (rate == 48_000) 4096 else -1 },
        )
        val coordinator = CommandCaptureCoordinator(direct, fallback, FakeRecognizerAdapter())
        val result = coordinator.start(FakeRecognitionListener())

        result.shouldBeInstanceOf<CommandCaptureStartResult.Fallback>()
        (result as CommandCaptureStartResult.Fallback).rateHz shouldBe 48_000
        coordinator.path shouldBe CommandCapturePath.FALLBACK
        direct.startCount shouldBe 1
        direct.shutdownCount shouldBe 1
        direct.isRunning().shouldBeFalse()
        fallback.openCount shouldBe 1
        fallback.session!!.started.shouldBeTrue()
        coordinator.isDirectRunning().shouldBeFalse()
        coordinator.isFallbackRunning().shouldBeTrue()
        coordinator.bothPathsRunning().shouldBeFalse()
    }

    "fallback chooses the first supported candidate in 48k then 44.1k then 32k then 8k order" {
        val direct = FakeDirectCapture(available = false)
        val fallback = FakeFallbackPcmCapture(
            probe = { rate ->
                when (rate) {
                    48_000 -> -1
                    44_100 -> 4096
                    32_000 -> 2048
                    8_000 -> 1024
                    else -> -1
                }
            },
            failOpenRates = setOf(44_100),
        )
        val coordinator = CommandCaptureCoordinator(direct, fallback, FakeRecognizerAdapter())
        val result = coordinator.start(FakeRecognitionListener())

        fallback.seenRateOrder.single().toList() shouldContainExactly
            AudioCaptureConfig.FALLBACK_RATES.toList()
        fallback.openAttempts shouldContainExactly listOf(44_100, 32_000)
        result.shouldBeInstanceOf<CommandCaptureStartResult.Fallback>()
        (result as CommandCaptureStartResult.Fallback).rateHz shouldBe 32_000
    }

    "a non-16 kHz production capture actually invokes resampling" {
        val fallback = FakeFallbackPcmCapture(probe = { 4096 })
        val recognizer = FakeRecognizerAdapter()
        val coordinator = CommandCaptureCoordinator(
            FakeDirectCapture(available = false),
            fallback,
            recognizer,
        )
        coordinator.start(FakeRecognitionListener())
        fallback.session!!.emit(ShortArray(480) { it.toShort() })

        coordinator.path shouldBe CommandCapturePath.FALLBACK
        coordinator.resampleInvocations shouldBeGreaterThan 0
        recognizer.accepted.isEmpty().shouldBeFalse()
        val fed = recognizer.accepted.sumOf { it.size }
        fed shouldBeInRange 140..180
        (fed < 480).shouldBeTrue()
    }

    "resampled PCM is passed to the Vosk recognizer adapter" {
        val fallback = FakeFallbackPcmCapture(
            probe = { rate -> if (rate == 48_000) 4096 else -1 },
        )
        val recognizer = FakeRecognizerAdapter()
        val listener = FakeRecognitionListener()
        val coordinator = CommandCaptureCoordinator(
            FakeDirectCapture(available = false),
            fallback,
            recognizer,
        )
        coordinator.start(listener)
        fallback.session!!.emit(ShortArray(960) { (it % 20).toShort() })

        coordinator.recognizerAccepts shouldBeGreaterThan 0
        recognizer.accepted.size shouldBe 1
        recognizer.accepted[0].size shouldBeInRange 300..340
        listener.partials.size shouldBe 1
    }

    "direct and fallback capture cannot run simultaneously" {
        val direct = FakeDirectCapture(available = true, startSucceeds = true)
        val fallback = FakeFallbackPcmCapture(probe = { 4096 })
        val coordinator = CommandCaptureCoordinator(direct, fallback, FakeRecognizerAdapter())
        coordinator.start(FakeRecognitionListener())
        coordinator.bothPathsRunning().shouldBeFalse()
        fallback.openCount shouldBe 0

        val failingDirect = FakeDirectCapture(available = true, startSucceeds = false)
        val fallback2 = FakeFallbackPcmCapture(probe = { 4096 })
        val coordinator2 = CommandCaptureCoordinator(failingDirect, fallback2, FakeRecognizerAdapter())
        coordinator2.start(FakeRecognitionListener())
        coordinator2.bothPathsRunning().shouldBeFalse()
        failingDirect.isRunning().shouldBeFalse()
        fallback2.session!!.started.shouldBeTrue()
        coordinator2.isFallbackRunning().shouldBeTrue()
    }

    "stop releases AudioRecord and the worker" {
        val fallback = FakeFallbackPcmCapture(probe = { 4096 })
        val coordinator = CommandCaptureCoordinator(
            FakeDirectCapture(available = false),
            fallback,
            FakeRecognizerAdapter(),
        )
        coordinator.start(FakeRecognitionListener())
        val session = fallback.session!!
        session.started.shouldBeTrue()
        coordinator.stop()
        session.released.shouldBeTrue()
        session.workerStopped.shouldBeTrue()
        session.started.shouldBeFalse()
        coordinator.isFallbackRunning().shouldBeFalse()
        coordinator.path shouldBe CommandCapturePath.NONE
    }

    "timeout returns CommandSession to IDLE_WAKE and resumes wake capture" {
        val machine = listeningSession()
        val wake = FakeWakeResume()
        val timeout = ManualTimeoutScheduler()
        val fallback = FakeFallbackPcmCapture(probe = { 4096 })
        val listener = object : FakeRecognitionListener() {
            override fun onTimeout() {
                super.onTimeout()
                endCommandSession(machine, wake)
            }
        }
        val coordinator = CommandCaptureCoordinator(
            FakeDirectCapture(available = false),
            fallback,
            FakeRecognizerAdapter(),
            timeoutScheduler = timeout,
        )
        coordinator.start(listener)
        machine.phase shouldBe CommandSessionPhase.COMMAND_LISTENING
        timeout.fire()
        listener.timeouts shouldBe 1
        fallback.session!!.released.shouldBeTrue()
        machine.phase shouldBe CommandSessionPhase.IDLE_WAKE
        machine.isBusy.shouldBeFalse()
        wake.resumed.shouldBeTrue()
    }

    "error returns CommandSession to IDLE_WAKE and resumes wake capture" {
        val machine = listeningSession()
        val wake = FakeWakeResume()
        val fallback = FakeFallbackPcmCapture(probe = { 4096 })
        val listener = object : FakeRecognitionListener() {
            override fun onError(exception: Exception) {
                super.onError(exception)
                endCommandSession(machine, wake)
            }
        }
        val coordinator = CommandCaptureCoordinator(
            FakeDirectCapture(available = false),
            fallback,
            FakeRecognizerAdapter(),
        )
        coordinator.start(listener)
        fallback.session!!.emitError(IOException("command-capture: read failed"))
        listener.errors.size shouldBe 1
        fallback.session!!.released.shouldBeTrue()
        machine.phase shouldBe CommandSessionPhase.IDLE_WAKE
        wake.resumed.shouldBeTrue()
    }

    "wake capture resumes after fallback success" {
        val machine = listeningSession()
        val wake = FakeWakeResume()
        val fallback = FakeFallbackPcmCapture(probe = { 4096 })
        val recognizer = FakeRecognizerAdapter().apply { nextIsFinal = true }
        val listener = object : FakeRecognitionListener() {
            override fun onResult(hypothesis: String) {
                super.onResult(hypothesis)
                endCommandSession(machine, wake)
            }
        }
        val coordinator = CommandCaptureCoordinator(
            FakeDirectCapture(available = false),
            fallback,
            recognizer,
        )
        coordinator.start(listener)
        fallback.session!!.emit(ShortArray(480) { 1 })
        listener.results.size shouldBe 1
        coordinator.stop()
        fallback.session!!.released.shouldBeTrue()
        machine.phase shouldBe CommandSessionPhase.IDLE_WAKE
        wake.resumed.shouldBeTrue()
    }

    "no usable fallback rate emits a command-capture error without leaving capture running" {
        val fallback = FakeFallbackPcmCapture(probe = { -1 })
        val coordinator = CommandCaptureCoordinator(
            FakeDirectCapture(available = false),
            fallback,
            FakeRecognizerAdapter(),
        )
        val result = coordinator.start(FakeRecognitionListener())
        result.shouldBeInstanceOf<CommandCaptureStartResult.Failed>()
        (result as CommandCaptureStartResult.Failed).cause.message shouldBe
            "command-capture: no usable microphone rate"
        coordinator.path shouldBe CommandCapturePath.NONE
        coordinator.isFallbackRunning().shouldBeFalse()
        coordinator.isDirectRunning().shouldBeFalse()
    }

    "12 native device support selects Path A" {
        AudioCaptureConfig.isNative16kHzSupported { 2048 }.shouldBeTrue()
        val direct = FakeDirectCapture(available = true, startSucceeds = true)
        val coordinator = CommandCaptureCoordinator(
            direct,
            FakeFallbackPcmCapture(probe = { 4096 }),
            FakeRecognizerAdapter(),
        )
        coordinator.start(FakeRecognitionListener()) shouldBe CommandCaptureStartResult.Direct
        coordinator.path shouldBe CommandCapturePath.DIRECT
    }

    "13 command PCM reaches Vosk on the fallback path" {
        val fallback = FakeFallbackPcmCapture(probe = { 4096 })
        val recognizer = FakeRecognizerAdapter()
        val coordinator = CommandCaptureCoordinator(
            FakeDirectCapture(available = false),
            fallback,
            recognizer,
        )
        coordinator.start(FakeRecognitionListener())
        fallback.session!!.emit(ShortArray(480) { 2 })
        coordinator.recognizerAccepts shouldBeGreaterThan 0
        recognizer.accepted.isEmpty().shouldBeFalse()
    }

    "14 Vietnamese partial and final transcript propagate" {
        val fallback = FakeFallbackPcmCapture(probe = { 4096 })
        val recognizer = FakeRecognizerAdapter().apply {
            partial = """{"partial":"mở you"}"""
            result = """{"text":"mở youtube"}"""
        }
        val listener = FakeRecognitionListener()
        val coordinator = CommandCaptureCoordinator(
            FakeDirectCapture(available = false),
            fallback,
            recognizer,
        )
        coordinator.start(listener)
        fallback.session!!.emit(ShortArray(480) { 1 })
        listener.partials.single() shouldBe """{"partial":"mở you"}"""
        recognizer.nextIsFinal = true
        fallback.session!!.emit(ShortArray(480) { 1 })
        listener.results.single() shouldBe """{"text":"mở youtube"}"""
        CarfuCommandRouter.match("mở youtube")!!.intent shouldBe CarfuIntent.OPEN_YOUTUBE
        VietnameseTranscript.isTooWeakToSubmit("mở youtube").shouldBeFalse()
    }

    "shared hub PCM is fed to Vosk without opening a second AudioRecord" {
        val direct = FakeDirectCapture(available = true, startSucceeds = true)
        val fallback = FakeFallbackPcmCapture(probe = { 4096 })
        val recognizer = FakeRecognizerAdapter()
        val listener = FakeRecognitionListener()
        val coordinator = CommandCaptureCoordinator(direct, fallback, recognizer)
        coordinator.start(listener) shouldBe CommandCaptureStartResult.Direct
        fallback.openCount shouldBe 0
        direct.emit(ShortArray(1152) { 12 })
        coordinator.recognizerAccepts shouldBeGreaterThan 0
        recognizer.accepted.single().size shouldBe 1152
        coordinator.resampleInvocations shouldBe 0
        coordinator.bothPathsRunning().shouldBeFalse()
        listener.partials.size shouldBe 1
    }

    "low-RMS command PCM still reaches Vosk" {
        CommandPcmStats.reset()
        val direct = FakeDirectCapture(available = true, startSucceeds = true)
        val fallback = FakeFallbackPcmCapture(probe = { 4096 })
        val recognizer = FakeRecognizerAdapter()
        val coordinator = CommandCaptureCoordinator(direct, fallback, recognizer)
        coordinator.start(FakeRecognitionListener()) shouldBe CommandCaptureStartResult.Direct
        val quiet = ShortArray(512) { if (it % 8 == 0) 3 else 0 }
        direct.emit(quiet)
        coordinator.recognizerAccepts shouldBeGreaterThan 0
        recognizer.accepted.single().size shouldBe 512
        CommandPcmStats.sampleCount() shouldBe 512
        CommandPcmStats.acceptCount() shouldBe 1
        fallback.openCount shouldBe 0
        coordinator.bothPathsRunning().shouldBeFalse()
    }

    "hub already recording refuses a second AudioRecord on Path B" {
        CarfuPcmHub.markRecording(true)
        val fallback = FakeFallbackPcmCapture(probe = { 4096 })
        val coordinator = CommandCaptureCoordinator(
            FakeDirectCapture(available = false),
            fallback,
            FakeRecognizerAdapter(),
        )
        val result = coordinator.start(FakeRecognitionListener())
        result.shouldBeInstanceOf<CommandCaptureStartResult.Failed>()
        (result as CommandCaptureStartResult.Failed).cause.message shouldBe
            "command-capture: hub recording; refusing second AudioRecord"
        fallback.openCount shouldBe 0
        coordinator.isFallbackRunning().shouldBeFalse()
        coordinator.isDirectRunning().shouldBeFalse()
        CarfuPcmHub.resetForTests()
    }

    "shared hub path timeout returns CommandSession to IDLE_WAKE" {
        val machine = listeningSession()
        val wake = FakeWakeResume()
        val timeout = ManualTimeoutScheduler()
        val direct = FakeDirectCapture(available = true, startSucceeds = true)
        val listener = object : FakeRecognitionListener() {
            override fun onTimeout() {
                super.onTimeout()
                endCommandSession(machine, wake)
            }
        }
        val coordinator = CommandCaptureCoordinator(
            direct,
            FakeFallbackPcmCapture(probe = { 4096 }),
            FakeRecognizerAdapter(),
            timeoutScheduler = timeout,
        )
        coordinator.start(listener) shouldBe CommandCaptureStartResult.Direct
        timeout.fire()
        listener.timeouts shouldBe 1
        direct.isRunning().shouldBeFalse()
        machine.phase shouldBe CommandSessionPhase.IDLE_WAKE
        wake.resumed.shouldBeTrue()
    }
})
