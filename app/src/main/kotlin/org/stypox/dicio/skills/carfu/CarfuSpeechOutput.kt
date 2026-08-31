package org.stypox.dicio.skills.carfu

import org.dicio.skill.context.SkillContext
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput

class CarfuSpeechOutput(
    private val speechVi: String,
) : HeadlineSpeechSkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String = speechVi
}
