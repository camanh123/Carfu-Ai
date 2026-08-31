package org.stypox.dicio.io.session

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class PcmHealthMonitorTest : StringSpec({
    "RECORDSTATE_RECORDING with peak=0 rms=0 is not healthy" {
        PcmHealthMonitor.isExactZero(0, 0.0) shouldBe true
        PcmHealthMonitor.isExactZero(1, 0.0) shouldBe false
        val (peak, rms) = PcmHealthMonitor.peakAndRms(ShortArray(160), 160)
        peak shouldBe 0
        rms shouldBe 0.0
    }

    "exact-zero PCM for the dead window requests one bounded restart" {
        val clock = mutableListOf(1_000L)
        val monitor = PcmHealthMonitor()
        monitor.onRecorderOpened()
        monitor.onFrame(clock[0], recording = true, peak = 0, rms = 0.0) shouldBe
            PcmHealthMonitor.Action.ACCUMULATING_ZERO
        clock[0] += PcmHealthMonitor.DEAD_WINDOW_MS
        monitor.onFrame(clock[0], recording = true, peak = 0, rms = 0.0) shouldBe
            PcmHealthMonitor.Action.RESTART
        clock[0] += PcmHealthMonitor.DEAD_WINDOW_MS
        monitor.onFrame(clock[0], recording = true, peak = 0, rms = 0.0) shouldBe
            PcmHealthMonitor.Action.DEAD_KEEP
    }

    "healthy PCM after a restart restores the health action" {
        val monitor = PcmHealthMonitor()
        monitor.onFrame(0L, recording = true, peak = 0, rms = 0.0)
        monitor.onFrame(PcmHealthMonitor.DEAD_WINDOW_MS, recording = true, peak = 0, rms = 0.0)
        monitor.onFrame(
            PcmHealthMonitor.DEAD_WINDOW_MS + 10,
            recording = true,
            peak = 400,
            rms = 80.0,
        ) shouldBe PcmHealthMonitor.Action.HEALTHY
    }
})
