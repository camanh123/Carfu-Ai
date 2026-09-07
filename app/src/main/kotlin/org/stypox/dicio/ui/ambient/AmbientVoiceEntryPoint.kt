package org.stypox.dicio.ui.ambient

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.stypox.dicio.eval.SkillEvaluator
import org.stypox.dicio.io.session.CommandSession

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AmbientVoiceEntryPoint {
    fun ambientVoiceOverlayController(): AmbientVoiceOverlayController
    fun skillEvaluator(): SkillEvaluator
    fun commandSession(): CommandSession
}
