package org.stypox.dicio.skills.volume

import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat.getSystemService
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import org.stypox.dicio.sentences.Sentences.Volume

class VolumeSkill(
    correspondingSkillInfo: SkillInfo,
    data: StandardRecognizerData<Volume>,
) : StandardRecognizerSkill<Volume>(correspondingSkillInfo, data) {

    override suspend fun generateOutput(ctx: SkillContext, inputData: Volume): SkillOutput {
        val audioManager = getSystemService(ctx.android, AudioManager::class.java)
            ?: return VolumeOutput(performedAction = null)

        val flags = AudioManager.FLAG_SHOW_UI
        when (inputData) {
            is Volume.Up -> audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_RAISE,
                flags,
            )
            is Volume.Down -> audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_LOWER,
                flags,
            )
            is Volume.Mute -> mute(audioManager, muted = true, flags = flags)
            is Volume.Unmute -> mute(audioManager, muted = false, flags = flags)
        }

        return VolumeOutput(performedAction = inputData)
    }

    private fun mute(audioManager: AudioManager, muted: Boolean, flags: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                flags,
            )
        } else {
            @Suppress("DEPRECATION")
            audioManager.setStreamMute(AudioManager.STREAM_MUSIC, muted)
        }
    }
}
