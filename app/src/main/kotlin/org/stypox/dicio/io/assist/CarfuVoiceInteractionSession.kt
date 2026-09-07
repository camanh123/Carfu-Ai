package org.stypox.dicio.io.assist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.voice.VoiceInteractionSession
import android.view.View
import dagger.hilt.android.EntryPointAccessors
import org.stypox.dicio.MainActivity
import org.stypox.dicio.io.session.CarfuDiag
import org.stypox.dicio.io.session.CarfuSessionGate
import org.stypox.dicio.ui.ambient.AmbientVoiceEntryPoint

/**
 * System assistant session for MODE / Assist. Shows no system VIS content view.
 *
 * When "Display over other apps" is granted, MODE triggers the existing V2 voice
 * spine without forcing MainActivity over Maps/YouTube; Ambient Voice HUD overlays.
 * Without overlay permission, falls back to the prior MainActivity Assist path.
 */
class CarfuVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    override fun onCreateContentView(): View? = null

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        CarfuDiag.assist(
            "VOICE_INTERACTION_SHOW flags=0x${Integer.toHexString(showFlags)} " +
                "extras=[${CarfuAssistIntents.summarizeSafeExtras(CarfuAssistIntents.extrasAsMap(args))}]",
        )
        if (tryCrossAppModeWithoutForegrounding()) {
            hide()
            return
        }
        startMainActivityForHardwareAssist(showFlags)
        hide()
    }

    override fun onHide() {
        CarfuDiag.assist("VOICE_INTERACTION_HIDE")
        super.onHide()
    }

    /**
     * @return true if MODE was dispatched without bringing MainActivity to front.
     */
    private fun tryCrossAppModeWithoutForegrounding(): Boolean {
        return try {
            if (!Settings.canDrawOverlays(context)) {
                CarfuDiag.assist("AMBIENT_CROSS_APP skipped reason=no_overlay_permission")
                return false
            }
            val ep = EntryPointAccessors.fromApplication(
                context.applicationContext,
                AmbientVoiceEntryPoint::class.java,
            )
            CarfuSessionGate.noteIncomingIntent(
                action = Intent.ACTION_ASSIST,
                component = "VoiceInteractionSession/cross_app",
            )
            ep.ambientVoiceOverlayController().ensureStarted()
            ep.skillEvaluator().onHardwareButtonDetected()
            CarfuDiag.assist("AMBIENT_CROSS_APP mode_triggered no_main_activity_reorder")
            true
        } catch (t: Throwable) {
            CarfuDiag.assist("AMBIENT_CROSS_APP_FALLBACK ${t.javaClass.simpleName}")
            false
        }
    }

    private fun startMainActivityForHardwareAssist(showFlags: Int) {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_ASSIST
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            )
            putExtra(CarfuAssistIntents.EXTRA_FROM_VOICE_INTERACTION, true)
            putExtra(CarfuAssistIntents.EXTRA_SHOW_FLAGS, showFlags)
        }
        CarfuSessionGate.noteIncomingIntent(
            action = Intent.ACTION_ASSIST,
            component = "VoiceInteractionSession",
        )
        try {
            startAssistantActivity(intent)
        } catch (throwable: Throwable) {
            CarfuDiag.assist("VOICE_INTERACTION_START_FALLBACK ${throwable.javaClass.simpleName}")
            context.startActivity(intent)
        }
    }
}
