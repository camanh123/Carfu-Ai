package org.stypox.dicio.sherpabenchmark.engine

/**
 * Conservative diagnostic classification only.
 * Never emits MEMORY_LEAK:YES. Never attributes growth to ONNX allocator/cache.
 */
enum class MemoryTrend {
    PLATEAU_LIKE,
    CONTINUING_GROWTH,
    INCONCLUSIVE,
}

object MemoryTrendHeuristic {
    const val PLATEAU_ABS_BYTES: Long = 8L * 1024L * 1024L
    const val GROWTH_ABS_BYTES: Long = 16L * 1024L * 1024L
    const val PLATEAU_REL: Double = 0.03
    const val GROWTH_REL: Double = 0.08

    const val EXPLANATION: String =
        "Conservative heuristic on native-heap deltas only. " +
            "PLATEAU_LIKE: late deltas (10->20, 20->30, and 30->40/40->50 if present) " +
            "are all <= max(8 MiB, 3% of NATIVE_HEAP_AFTER_WARMUP) AND no tracked delta " +
            "exceeds max(16 MiB, 8% of after-warmup). " +
            "CONTINUING_GROWTH: every available delta among 1->5, 5->10, 10->20, 20->30, " +
            "30->40, 40->50 is > max(16 MiB, 8% of NATIVE_HEAP_AFTER_WARMUP) (need >=3). " +
            "INCONCLUSIVE: mixed signs, large early growth with flat later samples, " +
            "missing samples, or small N. " +
            "Never labels MEMORY_LEAK:YES. Does not claim ONNX allocator/cache as the cause."

    fun classify(
        afterWarmupNative: Long,
        deltas: List<NamedDelta>,
    ): MemoryTrend {
        if (deltas.isEmpty() || afterWarmupNative < 0L) return MemoryTrend.INCONCLUSIVE
        val plateauCeil = maxOf(PLATEAU_ABS_BYTES, (afterWarmupNative * PLATEAU_REL).toLong())
        val growthFloor = maxOf(GROWTH_ABS_BYTES, (afterWarmupNative * GROWTH_REL).toLong())
        val lateKeys = setOf("10->20", "20->30", "30->40", "40->50")
        val allKeys = setOf("1->5", "5->10", "10->20", "20->30", "30->40", "40->50")
        val late = deltas.filter { it.label in lateKeys }
        val tracked = deltas.filter { it.label in allKeys }
        val anyLarge = tracked.any { it.deltaBytes > growthFloor }
        val anyNegativeBeyondNoise = tracked.any { it.deltaBytes < -plateauCeil }
        if (late.isNotEmpty() &&
            late.all { it.deltaBytes <= plateauCeil } &&
            !anyLarge &&
            !anyNegativeBeyondNoise
        ) {
            return MemoryTrend.PLATEAU_LIKE
        }
        if (tracked.size >= 3 && tracked.all { it.deltaBytes > growthFloor }) {
            return MemoryTrend.CONTINUING_GROWTH
        }
        return MemoryTrend.INCONCLUSIVE
    }
}

data class NamedDelta(
    val label: String,
    val deltaBytes: Long,
)
