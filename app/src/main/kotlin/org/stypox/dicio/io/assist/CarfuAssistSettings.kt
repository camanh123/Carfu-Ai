package org.stypox.dicio.io.assist

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import org.stypox.dicio.io.session.CarfuDiag

/**
 * Opens Android’s assistant / default-app settings so the user can set CARFU AI
 * as the default assistant for the steering MODE button.
 *
 * Never writes Secure/Global settings and never calls
 * [android.service.voice.VoiceInteractionService.setDisabled].
 */
object CarfuAssistSettings {
    fun openAssistantSettings(context: Context): Boolean {
        val pm = context.packageManager
        for (action in CarfuAssistIntents.assistantSettingsActions()) {
            val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(pm) == null) continue
            return try {
                context.startActivity(intent)
                CarfuDiag.assist("ASSIST_SETTINGS_OPEN action=$action")
                true
            } catch (_: ActivityNotFoundException) {
                CarfuDiag.assist("ASSIST_SETTINGS_MISSING action=$action")
                false
            }
        }
        CarfuDiag.assist("ASSIST_SETTINGS_UNAVAILABLE")
        return false
    }
}
