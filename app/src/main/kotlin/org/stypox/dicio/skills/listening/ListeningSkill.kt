package org.stypox.dicio.skills.listening

import kotlinx.coroutines.flow.first
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import org.stypox.dicio.io.wake.WakeService
import org.stypox.dicio.sentences.Sentences.Listening
import org.stypox.dicio.settings.datastore.BackgroundWake
import org.stypox.dicio.settings.datastore.WakeDevice

class ListeningSkill(val listeningInfo: ListeningInfo, data: StandardRecognizerData<Listening>) :
    StandardRecognizerSkill<Listening>(listeningInfo, data) {

    override suspend fun generateOutput(ctx: SkillContext, inputData: Listening): SkillOutput {
        if (listeningInfo.dataStore.data.first().wakeDevice == WakeDevice.WAKE_DEVICE_NOTHING) {
            return ListeningOutput(false, false, false)
        }

        val previouslyRunning = WakeService.isRunning()
        val shouldBeRunning = when (inputData) {
            is Listening.Start -> true
            is Listening.Stop -> false
        }
        listeningInfo.dataStore.updateData {
            it.toBuilder()
                .setBackgroundWake(
                    if (shouldBeRunning) BackgroundWake.BACKGROUND_WAKE_ENABLED
                    else BackgroundWake.BACKGROUND_WAKE_DISABLED
                )
                .build()
        }
        if (shouldBeRunning) {
            WakeService.start(ctx.android)
        } else {
            WakeService.disableAndStop(ctx.android)
        }
        return ListeningOutput(true, previouslyRunning, shouldBeRunning)
    }
}
