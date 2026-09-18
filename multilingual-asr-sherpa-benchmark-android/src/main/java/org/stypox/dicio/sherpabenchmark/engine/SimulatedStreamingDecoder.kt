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

    val partialHistory: List<PartialEvent> get() = partials.toList()
    val computeMs: Long get() = totalComputeMs
    val firstNonEmptyPartialElapsedMs: Long? get() = firstNonEmptyElapsedMs
    val lastPartialElapsedMs: Long? get() = partials.lastOrNull()?.sessionElapsedMs
    val partialCount: Int get() = partials.size

    fun tryPartial(samples: FloatArray, sessionElapsedMs: Long): PartialEvent? {
        if (samples.size - lastDecodedSampleCount < minNewSamples) return null
        if (samples.isEmpty()) return null
        val text = timedDecode(samples)
        lastDecodedSampleCount = samples.size
        if (text.isEmpty()) return null
        val event = PartialEvent(
            index = partials.size + 1,
            sessionElapsedMs = sessionElapsedMs,
            wallClockEpochMs = clock(),
            text = text,
            decodeMs = 0L, // filled below via lastComputeDelta
            audioMsDecoded = (samples.size * 1000L) / sampleRate,
        )
        val stamped = event.copy(decodeMs = lastDecodeMs)
        partials += stamped
        if (firstNonEmptyElapsedMs == null) firstNonEmptyElapsedMs = sessionElapsedMs
        return stamped
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
    }

    private var lastDecodeMs: Long = 0L

    private fun timedDecode(samples: FloatArray): String {
        val t0 = nanoTime()
        val raw = backend.decode(samples, sampleRate)
        lastDecodeMs = (nanoTime() - t0) / 1_000_000L
        totalComputeMs += lastDecodeMs
        // RAW output: do not trim except mapping null to empty. Preserve model text exactly.
        return raw
    }

    companion object {
        private const val HardFreezeChunkMs: Long = 200L
    }
}
