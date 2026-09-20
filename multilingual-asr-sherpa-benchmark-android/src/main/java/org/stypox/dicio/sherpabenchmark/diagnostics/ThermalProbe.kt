package org.stypox.dicio.sherpabenchmark.diagnostics

import android.content.Context
import android.os.Build
import android.os.PowerManager

object ThermalProbe {
    fun status(context: Context): String {
        return try {
            if (Build.VERSION.SDK_INT < 29) return "unavailable"
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                ?: return "unavailable"
            when (pm.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "NONE"
                PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
                PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
                PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
                PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
                else -> "code=${pm.currentThermalStatus}"
            }
        } catch (_: Throwable) {
            "unavailable"
        }
    }
}
