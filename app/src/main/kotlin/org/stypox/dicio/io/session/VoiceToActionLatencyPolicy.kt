package org.stypox.dicio.io.session

import org.stypox.dicio.io.input.CommandRecognitionPolicy

/**
 * Phase 4.4 — documents the voice-to-action latency path after semantic
 * COMPLETE partial commit.
 *
 * Device-proven (Phase 4.3b.2 sessions 15–16): COMPLETE OpenApp("YouTube") was
 * held until SR_HARD_CEILING (12s from startListening) because 4.3B.1 required
 * onEndOfSpeech that this OEM did not emit. The Android Intent executor is not
 * the delay (`action_to_intent` ≈ 7ms).
 *
 * Source-proven answers (this policy + call sites):
 * - OPEN_APP / PLAY_MEDIA may semantic-commit from a COMPLETE unique
 *   catalog / complete query+provider partial. No EOS. No stability timer.
 * - NAVIGATE candidates wait [StableCompletePartialPolicy.NAV_STABILIZATION_MS]
 *   after the last destination change, then EOS/Final or a NAV-only continuation
 *   grace if speech may still be active (`semantic_navigate_stable`). OpenApp /
 *   PlayMedia latency is unchanged.
 * - Incomplete Navigate never executes.
 * - First COMPLETE of an arbitrary ineligible intent never executes immediately.
 * - A 350ms hold timer must not commit.
 * - SR_HARD_CEILING remains a failsafe, not the normal OpenApp path.
 * - Execution **does not** wait for the 5s product timeout after speech.
 * - Execution **does not** wait for confirmation TTS to finish before startActivity.
 * - Recognizer silence extras are **not** set (OEM defaults apply).
 * - [android.speech.SpeechRecognizer.stopListening] is **not** called after EOS.
 */
object VoiceToActionLatencyPolicy {

    /** Product silence is a no-speech timeout only. After first speech/partial it is cancelled. */
    const val PRODUCT_NO_SPEECH_TIMEOUT_MS: Long = VoiceSessionManager.NO_SPEECH_TIMEOUT_MS

    fun waitsForAndroidFinalBeforeExecute(): Boolean = false

    fun executesFromCompletePartial(): Boolean = true

    fun productSilenceAppliesAfterSpeechDetected(): Boolean = false

    fun waitsForConfirmationTtsBeforeAndroidAction(): Boolean = false

    fun waitsForConfirmationTtsFinishBeforeAndroidAction(): Boolean = false

    fun setsRecognizerSilenceExtras(): Boolean = false

    fun maxSrRearms(): Int = CommandRecognitionPolicy.MAX_SR_REARMS

    /** Unsafe first-partial execute (no stability) remains disabled. */
    fun tryFastPartialExecutionEnabled(): Boolean = false

    fun stableCompletePartialEnabled(): Boolean = StableCompletePartialPolicy.isEnabled()

    fun callsStopListeningAfterEndOfSpeech(): Boolean =
        StableCompletePartialPolicy.callsStopListeningAfterEndOfSpeech()

    fun holdTimerMayCommit(): Boolean = StableCompletePartialPolicy.holdTimerMayCommit()

    fun navStabilizationMs(): Long = StableCompletePartialPolicy.NAV_STABILIZATION_MS

    fun requiresEndOfSpeechForStablePartial(): Boolean =
        StableCompletePartialPolicy.requiresEndOfSpeech()

    fun requiresEndOfSpeechForOpenAppSemanticCommit(): Boolean =
        StableCompletePartialPolicy.requiresEndOfSpeechForOpenApp()

    fun requiresEndOfSpeechForPlayMediaSemanticCommit(): Boolean =
        StableCompletePartialPolicy.requiresEndOfSpeechForPlayMedia()

    fun requiresEndOfSpeechForNavigateSemanticCommit(): Boolean =
        StableCompletePartialPolicy.requiresEndOfSpeechForNavigate()

    /**
     * RecognizerIntent extras that **are** currently put on the listening Intent.
     * Silence-length extras are intentionally absent (OEM-sensitive).
     */
    fun recognizerIntentExtrasCurrentlySet(): List<String> = listOf(
        "EXTRA_LANGUAGE_MODEL",
        "EXTRA_LANGUAGE",
        "EXTRA_LANGUAGE_PREFERENCE",
        "EXTRA_PARTIAL_RESULTS",
        "EXTRA_MAX_RESULTS",
        "EXTRA_PREFER_OFFLINE",
        "EXTRA_CALLING_PACKAGE",
    )

    fun recognizerSilenceExtrasCurrentlyUnset(): List<String> = listOf(
        "EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS",
        "EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS",
        "EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS",
    )

    /**
     * Device log fragment for the current Intent. Silence extras are **UNSET**
     * (constants exist on [CommandRecognitionPolicy] but are not put on the Intent).
     */
    fun recognizerSilenceExtrasLog(): String =
        "complete_silence=UNSET possibly_complete=UNSET minimum_length=UNSET " +
            "applied=false reason=oem_sensitive language=vi-VN partial=true " +
            "model=free_form max_results=3 prefer_offline=false"
}
