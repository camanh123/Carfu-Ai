package org.stypox.dicio.skills.volume

import org.dicio.skill.context.SkillContext
import org.stypox.dicio.R
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.sentences.Sentences.Volume
import org.stypox.dicio.util.getString

class VolumeOutput(
    private val performedAction: Volume?,
) : HeadlineSpeechSkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String = when (performedAction) {
        null -> ctx.getString(R.string.skill_volume_unavailable)
        is Volume.Up -> ctx.getString(R.string.skill_volume_up)
        is Volume.Down -> ctx.getString(R.string.skill_volume_down)
        is Volume.Mute -> ctx.getString(R.string.skill_volume_muted)
        is Volume.Unmute -> ctx.getString(R.string.skill_volume_unmuted)
    }
}
