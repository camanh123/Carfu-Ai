package org.stypox.dicio.io.assist

import android.service.voice.VoiceInteractionService
import org.stypox.dicio.io.session.CarfuDiag

/**
 * Official Android VoiceInteractionService so CARFU AI can be selected as the
 * default assistant on Android 10 (Assist & voice input).
 *
 * This service does not capture audio. MODE / assist dispatch is forwarded to
 * [org.stypox.dicio.MainActivity], which starts the existing CommandSession.
 */
class CarfuVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        CarfuDiag.assist("VOICE_INTERACTION_READY")
    }

    override fun onShutdown() {
        CarfuDiag.assist("VOICE_INTERACTION_SHUTDOWN")
        super.onShutdown()
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        super.onLaunchVoiceAssistFromKeyguard()
        CarfuDiag.assist("VOICE_INTERACTION_KEYGUARD_LAUNCH")
    }
}
