package org.stypox.dicio.io.session

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * COMMAND_LISTENING evidence for the shared 16 kHz hub. Counts frames that actually
 * reach [VoskRecognizerAdapter.acceptWaveForm], including low-RMS speech.
 */
object CommandPcmStats {
    private val framesRead = AtomicInteger()
    private val nonZeroFrames = AtomicInteger()
    private val samplesToVosk = AtomicInteger()
    private val acceptWaveFormCalls = AtomicInteger()
    private val partialCallbacks = AtomicInteger()
    private val finalCallbacks = AtomicInteger()
    private val peak0 = AtomicInteger()
    private val peak1to99 = AtomicInteger()
    private val peak100to999 = AtomicInteger()
    private val peak1000Plus = AtomicInteger()
    private val rms0 = AtomicInteger()
    private val rmsLow = AtomicInteger()
    private val rmsMid = AtomicInteger()
    private val rmsHigh = AtomicInteger()
    private val lastLogMs = AtomicLong(0L)

    fun reset() {
        framesRead.set(0)
        nonZeroFrames.set(0)
        samplesToVosk.set(0)
        acceptWaveFormCalls.set(0)
        partialCallbacks.set(0)
        finalCallbacks.set(0)
        peak0.set(0)
        peak1to99.set(0)
        peak100to999.set(0)
        peak1000Plus.set(0)
        rms0.set(0)
        rmsLow.set(0)
        rmsMid.set(0)
        rmsHigh.set(0)
        lastLogMs.set(0L)
    }

    fun onHubFrame(length: Int, peak: Int, rms: Double) {
        framesRead.incrementAndGet()
        if (peak != 0) nonZeroFrames.incrementAndGet()
        when {
            peak == 0 -> peak0.incrementAndGet()
            peak < 100 -> peak1to99.incrementAndGet()
            peak < 1000 -> peak100to999.incrementAndGet()
            else -> peak1000Plus.incrementAndGet()
        }
        when {
            rms <= 0.0 -> rms0.incrementAndGet()
            rms < 50.0 -> rmsLow.incrementAndGet()
            rms < 200.0 -> rmsMid.incrementAndGet()
            else -> rmsHigh.incrementAndGet()
        }
    }

    fun onAcceptWaveForm(sampleCount: Int) {
        acceptWaveFormCalls.incrementAndGet()
        samplesToVosk.addAndGet(sampleCount.coerceAtLeast(0))
    }

    fun onPartial() {
        partialCallbacks.incrementAndGet()
    }

    fun onFinal() {
        finalCallbacks.incrementAndGet()
    }

    fun snapshot(): String {
        return "framesRead=${framesRead.get()} nonZeroFrames=${nonZeroFrames.get()} " +
            "samplesToVosk=${samplesToVosk.get()} acceptWaveForm=${acceptWaveFormCalls.get()} " +
            "partials=${partialCallbacks.get()} finals=${finalCallbacks.get()} " +
            "peakBuckets=0:${peak0.get()},1-99:${peak1to99.get()}," +
            "100-999:${peak100to999.get()},1000+:${peak1000Plus.get()} " +
            "rmsBuckets=0:${rms0.get()},1-49:${rmsLow.get()}," +
            "50-199:${rmsMid.get()},200+:${rmsHigh.get()}"
    }

    fun maybeLog(nowMs: Long, intervalMs: Long = 1_000L) {
        val last = lastLogMs.get()
        if (last != 0L && nowMs - last < intervalMs) return
        if (!lastLogMs.compareAndSet(last, nowMs)) return
        CarfuLog.i(CommandSession.TAG, "COMMAND_PCM_STATS ${snapshot()}")
    }

    fun acceptCount(): Int = acceptWaveFormCalls.get()
    fun sampleCount(): Int = samplesToVosk.get()
}
