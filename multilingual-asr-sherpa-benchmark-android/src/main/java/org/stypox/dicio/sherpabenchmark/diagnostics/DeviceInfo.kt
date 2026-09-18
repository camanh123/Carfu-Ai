package org.stypox.dicio.sherpabenchmark.diagnostics

import android.os.Build
import java.io.File

object DeviceInfo {
    fun abi(): String = Build.SUPPORTED_ABIS.joinToString(",")

    fun api(): Int = Build.VERSION.SDK_INT

    fun cpuCores(): Int = Runtime.getRuntime().availableProcessors()

    /**
     * Best-effort CPU feature string without privileged APIs.
     * May be empty if /proc/cpuinfo is unreadable.
     */
    @Suppress("DEPRECATION")
    fun cpuFeatures(): String {
        val abi = "SUPPORTED_ABIS=${Build.SUPPORTED_ABIS.joinToString("|")};" +
            "CPU_ABI=${Build.CPU_ABI};CPU_ABI2=${Build.CPU_ABI2};" +
            "HARDWARE=${Build.HARDWARE};BOARD=${Build.BOARD};SOC=${soc()}"
        val proc = readProcCpuinfoFeatures()
        return if (proc.isBlank()) abi else "$abi;proc_features=$proc"
    }

    private fun soc(): String {
        return if (Build.VERSION.SDK_INT >= 31) {
            "${Build.SOC_MANUFACTURER}/${Build.SOC_MODEL}"
        } else {
            "n/a"
        }
    }

    private fun readProcCpuinfoFeatures(): String {
        return try {
            File("/proc/cpuinfo").useLines { lines ->
                lines.firstOrNull { it.startsWith("Features") || it.startsWith("CPU architecture") }
                    ?: ""
            }
        } catch (_: Throwable) {
            ""
        }
    }
}
