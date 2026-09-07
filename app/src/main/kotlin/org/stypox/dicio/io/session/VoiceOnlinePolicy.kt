package org.stypox.dicio.io.session

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Online-first gate for V2 voice sessions.
 * Internet recovery alone must never create a session — user must press MODE again.
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

    fun mayEnterOnlineVoiceSession(online: Boolean): Boolean = online

    fun resetForTests() {
        onlineOverride = null
    }
}
