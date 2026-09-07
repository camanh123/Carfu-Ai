package org.stypox.dicio.ui.ambient

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.stypox.dicio.io.session.CarfuLog

/**
 * Safe UX helpers for Android "Display over other apps" (SYSTEM_ALERT_WINDOW).
 *
 * Never creates VoiceSession, starts SpeechRecognizer, speaks TTS, executes
 * commands, or enables background auto-trigger.
 */
object AmbientOverlayPermission {
    const val TAG = "CarfuAmbient"

    /** JVM-testable grant check; [overrideGranted] used by unit tests. */
    var overrideGranted: Boolean? = null

    const val ACTION_MANAGE_OVERLAY_PERMISSION =
        "android.settings.action.MANAGE_OVERLAY_PERMISSION"

    fun isGranted(context: Context? = null): Boolean {
        overrideGranted?.let { return it }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        if (context == null) return false
        return try {
            Settings.canDrawOverlays(context)
        } catch (_: Throwable) {
            false
        }
    }

    fun packageOverlayUriString(packageName: String): String = "package:$packageName"

    fun packageOverlayUri(packageName: String): Uri =
        Uri.parse(packageOverlayUriString(packageName))

    fun manageOverlayIntent(packageName: String): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            packageOverlayUri(packageName),
        )

    /**
     * Opens the package-specific overlay permission screen.
     * Call only from an explicit user tap — never auto-launch.
     */
    fun openManageOverlayPermission(context: Context): Boolean {
        val intent = manageOverlayIntent(context.packageName)
        return try {
            context.startActivity(intent)
            CarfuLog.i(TAG, "OVERLAY_PERMISSION_SETTINGS_OPEN package=${context.packageName}")
            true
        } catch (_: ActivityNotFoundException) {
            CarfuLog.w(TAG, "OVERLAY_PERMISSION_SETTINGS_MISSING")
            false
        } catch (t: Throwable) {
            CarfuLog.w(TAG, "OVERLAY_PERMISSION_SETTINGS_FAILED ${t.javaClass.simpleName}")
            false
        }
    }

    /** Presentation only — does not mutate voice state. */
    fun statusLabelEnabled(): Boolean = true

    fun statusLabelDisabled(): Boolean = false

    fun resetForTests() {
        overrideGranted = null
    }
}
