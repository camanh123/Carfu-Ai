package org.stypox.dicio.sherpabenchmark.metrics

import java.util.Locale

data class MemorySnapshot(
    val javaUsedBytes: Long,
    val javaTotalBytes: Long,
    val javaMaxBytes: Long,
    val nativeHeapAllocatedBytes: Long,
    val pssKb: Long,
    val availMemBytes: Long,
    val totalMemBytes: Long,
    val thresholdBytes: Long,
    val lowMemory: Boolean,
) {
    fun summaryLine(label: String): String = buildString {
        append(label)
        append(": javaUsed=")
        append(formatMb(javaUsedBytes))
        append(" javaTotal=")
        append(formatMb(javaTotalBytes))
        append(" javaMax=")
        append(formatMb(javaMaxBytes))
        append(" nativeHeap=")
        append(formatMb(nativeHeapAllocatedBytes))
        append(" pss=")
        append(formatMb(pssKb * 1024L))
        append(" availMem=")
        append(formatMb(availMemBytes))
        append(" lowMemory=")
        append(lowMemory)
    }

    companion object {
        fun formatMb(bytes: Long): String {
            if (bytes < 0L) return "n/a"
            return String.format(Locale.US, "%.1fMB", bytes / (1024.0 * 1024.0))
        }
    }
}

data class PeakMemory(
    val peakJavaUsedBytes: Long,
    val peakNativeHeapBytes: Long,
    val peakPssKb: Long,
) {
    fun summaryLine(): String = buildString {
        append("PEAK_MEMORY (sampled peak): javaUsed=")
        append(MemorySnapshot.formatMb(peakJavaUsedBytes))
        append(" nativeHeap=")
        append(MemorySnapshot.formatMb(peakNativeHeapBytes))
        append(" pss=")
        append(MemorySnapshot.formatMb(peakPssKb * 1024L))
        append(" (sampled; not claimed as exact peak)")
    }
}
