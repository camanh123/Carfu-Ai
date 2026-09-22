package org.stypox.dicio.sherpabenchmark.engine

import java.util.concurrent.atomic.AtomicInteger

/**
 * Bounded/coalesced partial-decode policy for OPTIMIZED interactive mode.
 * Does not call the recognizer. Does not inspect transcript text.
 */
class OptimizedPartialPolicy(
    private val firstPartialAudioMs: Long = InteractiveOptimizeLimits.FIRST_PARTIAL_AUDIO_MS,
    private val minNewAudioMs: Long = InteractiveOptimizeLimits.MIN_NEW_AUDIO_MS,
    private val maxNewAudioWaitMs: Long = InteractiveOptimizeLimits.MAX_NEW_AUDIO_WAIT_MS,
    private val sampleRate: Int = InteractiveOptimizeLimits.SAMPLE_RATE_HZ,
) {
    val requested = AtomicInteger(0)
    val executed = AtomicInteger(0)
    val coalesced = AtomicInteger(0)
    val skippedBusy = AtomicInteger(0)
    val skippedNoNewAudio = AtomicInteger(0)
    val skippedTooEarly = AtomicInteger(0)
    val finalDecodeCount = AtomicInteger(0)

    @Volatile
    var firstPartialCompleted: Boolean = false
        private set

    @Volatile
    var lastDecodeMs: Long = 0L
        private set

    @Volatile
    var lastDecodedSamples: Int = 0
        private set

    @Volatile
    var stopRequested: Boolean = false
        private set

    fun reset() {
        requested.set(0)
        executed.set(0)
        coalesced.set(0)
        skippedBusy.set(0)
        skippedNoNewAudio.set(0)
        skippedTooEarly.set(0)
        finalDecodeCount.set(0)
        firstPartialCompleted = false
        lastDecodeMs = 0L
        lastDecodedSamples = 0
        stopRequested = false
    }

    fun markStop() {
        stopRequested = true
    }

    fun adaptiveMinNewSamples(lastDecodeDurationMs: Long): Int {
        val waitMs = when {
            lastDecodeDurationMs <= 0L -> minNewAudioMs
            else -> lastDecodeDurationMs.coerceIn(minNewAudioMs, maxNewAudioWaitMs)
        }
        return ((sampleRate.toLong() * waitMs) / 1000L).toInt().coerceAtLeast(1)
    }

    fun decide(
        audioMs: Long,
        currentSamples: Int,
        inFlight: Boolean,
        pending: Boolean,
    ): PartialScheduleDecision {
        if (stopRequested) return PartialScheduleDecision.SKIP_BUSY
        if (!firstPartialCompleted) {
            if (audioMs < firstPartialAudioMs) {
                skippedTooEarly.incrementAndGet()
                return PartialScheduleDecision.SKIP_TOO_EARLY
            }
            // Do not queue a second full-audio snapshot while the first decode is busy.
            if (inFlight) {
                skippedBusy.incrementAndGet()
                return PartialScheduleDecision.SKIP_BUSY
            }
            requested.incrementAndGet()
            return PartialScheduleDecision.REQUEST
        }
        val newSamples = currentSamples - lastDecodedSamples
        val need = adaptiveMinNewSamples(lastDecodeMs)
        if (newSamples < need) {
            skippedNoNewAudio.incrementAndGet()
            return PartialScheduleDecision.SKIP_NO_NEW_AUDIO
        }
        return scheduleOrBusy(inFlight, pending)
    }

    private fun scheduleOrBusy(inFlight: Boolean, pending: Boolean): PartialScheduleDecision {
        requested.incrementAndGet()
        if (!inFlight) return PartialScheduleDecision.REQUEST
        if (pending) {
            skippedBusy.incrementAndGet()
            return PartialScheduleDecision.SKIP_BUSY
        }
        coalesced.incrementAndGet()
        return PartialScheduleDecision.COALESCE
    }

    fun onPartialExecuted(decodedSamples: Int, decodeMs: Long) {
        executed.incrementAndGet()
        lastDecodedSamples = decodedSamples
        lastDecodeMs = decodeMs
        firstPartialCompleted = true
    }

    fun onFinalExecuted() {
        finalDecodeCount.incrementAndGet()
    }

    fun snapshot(): InteractiveDecodeStats = InteractiveDecodeStats(
        requested = requested.get(),
        executed = executed.get(),
        coalesced = coalesced.get(),
        skippedBusy = skippedBusy.get(),
        skippedNoNewAudio = skippedNoNewAudio.get(),
        skippedTooEarly = skippedTooEarly.get(),
        finalDecodeCount = finalDecodeCount.get(),
    )
}

data class InteractiveDecodeStats(
    val requested: Int = 0,
    val executed: Int = 0,
    val coalesced: Int = 0,
    val skippedBusy: Int = 0,
    val skippedNoNewAudio: Int = 0,
    val skippedTooEarly: Int = 0,
    val finalDecodeCount: Int = 0,
)
