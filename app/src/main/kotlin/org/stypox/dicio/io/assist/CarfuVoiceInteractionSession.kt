package org.stypox.dicio.io.assist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View
import org.stypox.dicio.MainActivity
import org.stypox.dicio.io.session.CarfuDiag

/**
 * System assistant session for MODE / Assist. Shows no overlay and does not
 * capture audio. The existing DrivingScreen + CommandSession + shared 16 kHz
 * AudioRecord handle listening after MainActivity receives ACTION_ASSIST.
 */
class CarfuVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    override fun onCreateContentView(): View? = null

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        CarfuDiag.assist(
            "VOICE_INTERACTION_SHOW flags=0x${Integer.toHexString(showFlags)} " +
                "extras=[${CarfuAssistIntents.summarizeSafeExtras(CarfuAssistIntents.extrasAsMap(args))}]",
        )
        startMainActivityForHardwareAssist(showFlags)
        hide()
    }

    override fun onHide() {
        CarfuDiag.assist("VOICE_INTERACTION_HIDE")
        super.onHide()
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
        try {
            startAssistantActivity(intent)
        } catch (throwable: Throwable) {
            CarfuDiag.assist("VOICE_INTERACTION_START_FALLBACK ${throwable.javaClass.simpleName}")
            context.startActivity(intent)
        }
    }
}
