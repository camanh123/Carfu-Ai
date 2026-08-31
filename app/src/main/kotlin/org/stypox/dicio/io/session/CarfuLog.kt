package org.stypox.dicio.io.session

import android.util.Log

/**
 * [android.util.Log] throws in unmocked JVM unit tests. Production still writes to logcat.
 */
internal object CarfuLog {
    fun i(tag: String, message: String) {
        try {
            Log.i(tag, message)
        } catch (_: Throwable) {
        }
        CarfuDiag.record(tag, message)
    }

    fun w(tag: String, message: String) {
        try {
            Log.w(tag, message)
        } catch (_: Throwable) {
        }
        CarfuDiag.record(tag, message)
    }

    fun e(tag: String, message: String) {
        try {
            Log.e(tag, message)
        } catch (_: Throwable) {
        }
        CarfuDiag.record(tag, message)
    }
}
