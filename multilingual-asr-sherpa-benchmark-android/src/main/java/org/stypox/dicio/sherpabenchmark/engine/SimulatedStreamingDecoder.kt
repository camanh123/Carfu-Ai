package org.stypox.dicio.sherpabenchmark.engine

fun interface AsrBackend {
    /** Decode the full accumulated waveform. Returns RAW model text unmodified. */
    fun decode(samples: FloatArray, sampleRate: Int): String
}

data class PartialEvent(
    val index: Int,
    val sessionElapsedMs: Long,
    val wallClockEpochMs: Long,
    val text: String,
    val decodeMs: Long,
    val audioMsDecoded: Long,
)

data class FinalDecodeResult(
    val text: String,
    val decodeMs: Long,
    val totalComputeMs: Long,
    val partials: List<PartialEvent>,
    val firstNonEmptyPartialElapsedMs: Long?,
    val lastPartialElapsedMs: Long?,
)

/**
 * Simulated streaming for an OFFLINE transducer:
 * re-decode the accumulated waveform whenever enough new audio has arrived.
 * This is NOT a native streaming Zipformer.
 */
class SimulatedStreamingDecoder(
    private val backend: AsrBackend,
    private val sampleRate: Int = 16_000,
    private val minNewSamples: Int = ((16_000L * HardFreezeChunkMs) / 1000L).toInt(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val nanoTime: () -> Long = { System.nanoTime() },
) {
    private val partials = ArrayList<PartialEvent>()
    private var lastDecodedSampleCount: Int = 0
    private var totalComputeMs: Long = 0L
    private var firstNonEmptyElapsedMs: Long? = null
    private var decodeCount: Int = 0
    private var maxDecodeMs: Long = 0L

    val partialHistory: List<PartialEvent> get() = partials.toList()
    val computeMs: Long get() = totalComputeMs
    val firstNonEmptyPartialElapsedMs: Long? get() = firstNonEmptyElapsedMs
    val lastPartialElapsedMs: Long? get() = partials.lastOrNull()?.sessionElapsedMs
    val partialCount: Int get() = totalPartialEvents
    val decodeAttempts: Int get() = decodeCount
    val maximumDecodeMs: Long get() = maxDecodeMs
    val lastDecodeDurationMs: Long get() = lastDecodeMs
    private var totalPartialEvents: Int = 0

    fun tryPartial(samples: FloatArray, sessionElapsedMs: Long): PartialEvent? {
        if (samples.size - lastDecodedSampleCount < minNewSamples) return null
        if (samples.isEmpty()) return null
        val text = timedDecode(samples)
        lastDecodedSampleCount = samples.size
        if (text.isEmpty()) return null
        totalPartialEvents += 1
        val event = PartialEvent(
            index = totalPartialEvents,
            sessionElapsedMs = sessionElapsedMs,
            wallClockEpochMs = clock(),
            text = text,
            decodeMs = lastDecodeMs,
            audioMsDecoded = (samples.size * 1000L) / sampleRate,
        )
        partials += event
        while (partials.size > org.stypox.dicio.sherpabenchmark.freeze.BenchmarkLimits.MAX_PARTIAL_EVENTS) {
            partials.removeAt(0)
        }
        if (firstNonEmptyElapsedMs == null) firstNonEmptyElapsedMs = sessionElapsedMs
        return event
    }

    fun finalize(samples: FloatArray): FinalDecodeResult {
        val text = if (samples.isEmpty()) "" else timedDecode(samples)
        lastDecodedSampleCount = samples.size
        return FinalDecodeResult(
            text = text,
            decodeMs = lastDecodeMs,
            totalComputeMs = totalComputeMs,
            partials = partials.toList(),
            firstNonEmptyPartialElapsedMs = firstNonEmptyElapsedMs,
            lastPartialElapsedMs = partials.lastOrNull()?.sessionElapsedMs,
        )
    }

    fun reset() {
        partials.clear()
        lastDecodedSampleCount = 0
        totalComputeMs = 0L
        firstNonEmptyElapsedMs = null
        lastDecodeMs = 0L
        decodeCount = 0
        maxDecodeMs = 0L
        totalPartialEvents = 0
    }

    private var lastDecodeMs: Long = 0L

    private fun timedDecode(samples: FloatArray): String {
        val t0 = nanoTime()
        val raw = backend.decode(samples, sampleRate)
        lastDecodeMs = (nanoTime() - t0) / 1_000_000L
        totalComputeMs += lastDecodeMs
        decodeCount += 1
        if (lastDecodeMs > maxDecodeMs) maxDecodeMs = lastDecodeMs
        // RAW output: do not trim except mapping null to empty. Preserve model text exactly.
        return raw
    }

    companion object {
        private const val HardFreezeChunkMs: Long = 200L
    }
}
