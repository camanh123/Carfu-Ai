package org.stypox.dicio.io.session

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Connectivity snapshot for V2 voice sessions.
 *
 * A deliberate MODE/UI press must not be abandoned because ConnectivityManager
 * briefly reports no usable internet. Real SpeechRecognizer / network failures
 * are handled inside the created session. Internet recovery alone must never
 * create a later session — the user must press MODE again.
 */
object VoiceOnlinePolicy {
    const val OFFLINE_TTS_VI = "Vui lòng kết nối Internet để sử dụng dịch vụ."

    /** Injectable for JVM tests. */
    var onlineOverride: Boolean? = null

    fun isUsableInternet(context: Context? = null): Boolean {
        onlineOverride?.let { return it }
        if (context == null) return false
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * MODE/UI fail-open: a false ConnectivityManager snapshot does not block
     * VoiceSession creation. [online] is retained for callers/tests.
     */
    @Suppress("UNUSED_PARAMETER")
    fun mayEnterOnlineVoiceSession(online: Boolean): Boolean = true

    fun autoRetryOnNetworkRecovery(): Boolean =
        ModeVoiceEntryPolicy.autoRetryOnNetworkRecovery()

    fun resetForTests() {
        onlineOverride = null
    }
}
