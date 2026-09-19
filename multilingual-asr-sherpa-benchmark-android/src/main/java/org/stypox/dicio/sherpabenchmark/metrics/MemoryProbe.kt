package org.stypox.dicio.sherpabenchmark.metrics

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class MemoryProbe(private val context: Context) {
    fun snapshot(): MemorySnapshot {
        val rt = Runtime.getRuntime()
        val mi = ActivityManager.MemoryInfo()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.getMemoryInfo(mi)
        return MemorySnapshot(
            javaUsedBytes = rt.totalMemory() - rt.freeMemory(),
            javaTotalBytes = rt.totalMemory(),
            javaMaxBytes = rt.maxMemory(),
            nativeHeapAllocatedBytes = Debug.getNativeHeapAllocatedSize(),
            pssKb = Debug.getPss().toLong(),
            availMemBytes = mi.availMem,
            totalMemBytes = mi.totalMem,
            thresholdBytes = mi.threshold,
            lowMemory = mi.lowMemory,
        )
    }

    fun startPeakSampler(intervalMs: Long = 100L): PeakSampler {
        return PeakSampler(this, intervalMs).also { it.start() }
    }

    class PeakSampler(
        private val probe: MemoryProbe,
        private val intervalMs: Long,
    ) {
        private val running = AtomicBoolean(false)
        private val peakJava = AtomicLong(0)
        private val peakNative = AtomicLong(0)
        private val peakPss = AtomicLong(0)
        @Volatile
        var latest: MemorySnapshot? = null
            private set
        private val exec = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "sherpa-mem-peak").apply { isDaemon = true }
        }

        fun start() {
            if (!running.compareAndSet(false, true)) return
            sampleOnce()
            exec.scheduleAtFixedRate(
                { if (running.get()) sampleOnce() },
                intervalMs,
                intervalMs,
                TimeUnit.MILLISECONDS,
            )
        }

        private fun sampleOnce() {
            val s = probe.snapshot()
            latest = s
            peakJava.updateAndGet { cur -> maxOf(cur, s.javaUsedBytes) }
            peakNative.updateAndGet { cur -> maxOf(cur, s.nativeHeapAllocatedBytes) }
            peakPss.updateAndGet { cur -> maxOf(cur, s.pssKb) }
        }

        fun stop(): PeakMemory {
            running.set(false)
            exec.shutdownNow()
            sampleOnce()
            return PeakMemory(
                peakJavaUsedBytes = peakJava.get(),
                peakNativeHeapBytes = peakNative.get(),
                peakPssKb = peakPss.get(),
            )
        }
    }
}
