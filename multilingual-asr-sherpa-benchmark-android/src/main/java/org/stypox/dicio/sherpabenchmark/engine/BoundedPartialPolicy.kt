package org.stypox.dicio.sherpabenchmark.engine

import java.util.concurrent.atomic.AtomicInteger

/**
 * Audio-progress partial budget for BOUNDED interactive mode.
 * Does not call the recognizer. Does not inspect transcript text.
 * Does not change the failed 3B.1.3 OPTIMIZED scheduler.
 */
object BoundedPartialLimits {
    const val FIRST_PARTIAL_AUDIO_MS: Long = 700L
    const val NEW_AUDIO_MS: Long = 900L
    const val MAX_PARTIAL_DECODES: Int = 5
    const val POLL_MS: Long = 100L
    const val SAMPLE_RATE_HZ: Int = 16_000
}

enum class BoundedScheduleDecision {
    REQUEST,
    SKIP_TOO_EARLY,
    SKIP_NO_NEW_AUDIO,
    SKIP_BUSY,
    DROP_BUDGET,
    DROP_STOP,
}

data class BoundedPartialLog(
    val index: Int,
    val audioSnapshotMs: Long,
    val newAudioSincePreviousMs: Long,
    val decodeDurationMs: Long,
)

data class BoundedDecodeStats(
    val requested: Int = 0,
    val executed: Int = 0,
    val coalesced: Int = 0,
    val skippedBusy: Int = 0,
    val droppedBudget: Int = 0,
    val droppedStop: Int = 0,
    val finalDecodeCount: Int = 0,
    val logs: List<BoundedPartialLog> = emptyList(),
)

class BoundedPartialPolicy(
    private val firstPartialAudioMs: Long = BoundedPartialLimits.FIRST_PARTIAL_AUDIO_MS,
    private val newAudioMs: Long = BoundedPartialLimits.NEW_AUDIO_MS,
    private val maxPartialDecodes: Int = BoundedPartialLimits.MAX_PARTIAL_DECODES,
    private val sampleRate: Int = BoundedPartialLimits.SAMPLE_RATE_HZ,
) {
    val requested = AtomicInteger(0)
    val executed = AtomicInteger(0)
    val skippedBusy = AtomicInteger(0)
    val skippedTooEarly = AtomicInteger(0)
    val skippedNoNewAudio = AtomicInteger(0)
    val droppedBudget = AtomicInteger(0)
    val droppedStop = AtomicInteger(0)
    val finalDecodeCount = AtomicInteger(0)

    @Volatile
    var stopRequested: Boolean = false
        private set

    @Volatile
    var lastSnapshotSamples: Int = 0
        private set

    @Volatile
    var lastSnapshotMs: Long = 0L
        private set

    private val logs = ArrayList<BoundedPartialLog>()

    fun reset() {
        requested.set(0)
        executed.set(0)
        skippedBusy.set(0)
        skippedTooEarly.set(0)
        skippedNoNewAudio.set(0)
        droppedBudget.set(0)
        droppedStop.set(0)
        finalDecodeCount.set(0)
        stopRequested = false
        lastSnapshotSamples = 0
        lastSnapshotMs = 0L
        synchronized(logs) { logs.clear() }
    }

    fun markStop() {
        stopRequested = true
    }

    fun newAudioMsSincePrevious(currentSamples: Int): Long {
        val newSamples = (currentSamples - lastSnapshotSamples).coerceAtLeast(0)
        return (newSamples * 1000L) / sampleRate
    }

    fun canExecutePartial(): Boolean =
        !stopRequested && executed.get() < maxPartialDecodes

    fun decide(
        audioMs: Long,
        currentSamples: Int,
        inFlight: Boolean,
        recording: Boolean,
    ): BoundedScheduleDecision {
        if (stopRequested || !recording) {
            droppedStop.incrementAndGet()
            return BoundedScheduleDecision.DROP_STOP
        }
        if (executed.get() >= maxPartialDecodes) {
            droppedBudget.incrementAndGet()
            return BoundedScheduleDecision.DROP_BUDGET
        }
        if (inFlight) {
            skippedBusy.incrementAndGet()
            return BoundedScheduleDecision.SKIP_BUSY
        }
        if (executed.get() == 0) {
            if (audioMs < firstPartialAudioMs) {
                skippedTooEarly.incrementAndGet()
                return BoundedScheduleDecision.SKIP_TOO_EARLY
            }
            requested.incrementAndGet()
            return BoundedScheduleDecision.REQUEST
        }
        if (newAudioMsSincePrevious(currentSamples) < newAudioMs) {
            skippedNoNewAudio.incrementAndGet()
            return BoundedScheduleDecision.SKIP_NO_NEW_AUDIO
        }
        requested.incrementAndGet()
        return BoundedScheduleDecision.REQUEST
    }

    fun onPartialExecuted(decodedSamples: Int, decodeMs: Long) {
        val snapshotMs = (decodedSamples.toLong() * 1000L) / sampleRate
        val newMs = if (executed.get() == 0) snapshotMs else newAudioMsSincePrevious(decodedSamples)
        val index = executed.incrementAndGet()
        lastSnapshotSamples = decodedSamples
        lastSnapshotMs = snapshotMs
        synchronized(logs) {
            logs += BoundedPartialLog(
                index = index,
                audioSnapshotMs = snapshotMs,
                newAudioSincePreviousMs = newMs,
                decodeDurationMs = decodeMs,
            )
        }
    }

    fun onFinalExecuted() {
        finalDecodeCount.incrementAndGet()
    }

    fun partialLogs(): List<BoundedPartialLog> = synchronized(logs) { logs.toList() }

    fun snapshot(): BoundedDecodeStats = BoundedDecodeStats(
        requested = requested.get(),
        executed = executed.get(),
        coalesced = 0,
        skippedBusy = skippedBusy.get(),
        droppedBudget = droppedBudget.get(),
        droppedStop = droppedStop.get(),
        finalDecodeCount = finalDecodeCount.get(),
        logs = partialLogs(),
    )
}
