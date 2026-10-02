package org.stypox.dicio.io.input

import org.stypox.dicio.io.wake.BackgroundWakePolicy

/**
 * Isolation flags for the cold-MODE latency experiment.
 *
 * ASR A/B classes remain in the tree. Production MODE listening must not
 * consult [AsrTestProfileController] / `carfu_asr_test_profile`.
 * OpenWakeWord source remains; production MODE must not construct it.
 */
object ColdModeIsolation {
    /** Parent of the A/B experiment commit dc480a22. */
    const val PRE_AB_REF_COMMIT = "7bdb5be65cae9b2ff4044637e2064fc35331e9a6"

    const val ASR_AB_PRODUCTION = "UNPLUGGED"
    const val OPENWAKEWORD_PRODUCTION = "UNPLUGGED"

    fun productionConsultsAsrProfileSelector(): Boolean = false

    fun shouldConstructOpenWakeWord(): Boolean =
        !BackgroundWakePolicy.modeOnlyForceBackgroundWakeOff()

    /**
     * Extras restored from [PRE_AB_REF_COMMIT]
     * `AndroidSpeechInputDevice.recognizerIntent()`.
     */
    fun productionRecognizerExtras(callingPackage: String): List<AsrIntentExtra> {
        val cfg = CommandRecognitionPolicy.recognizerIntentConfig()
        return listOf(
            AsrIntentExtra.Str(
                AsrRecognizerIntentProfiles.EXTRA_LANGUAGE_MODEL,
                cfg.languageModel,
            ),
            AsrIntentExtra.Str(
                AsrRecognizerIntentProfiles.EXTRA_LANGUAGE,
                cfg.language,
            ),
            AsrIntentExtra.Str(
                AsrRecognizerIntentProfiles.EXTRA_LANGUAGE_PREFERENCE,
                cfg.language,
            ),
            AsrIntentExtra.Bool(
                AsrRecognizerIntentProfiles.EXTRA_PARTIAL_RESULTS,
                cfg.partialResults,
            ),
            AsrIntentExtra.IntVal(
                AsrRecognizerIntentProfiles.EXTRA_MAX_RESULTS,
                cfg.maxResults,
            ),
            AsrIntentExtra.Bool(
                AsrRecognizerIntentProfiles.EXTRA_PREFER_OFFLINE,
                cfg.preferOffline,
            ),
            AsrIntentExtra.Str(
                AsrRecognizerIntentProfiles.EXTRA_CALLING_PACKAGE,
                callingPackage,
            ),
        )
    }
}
