package org.stypox.dicio.io.session

import org.stypox.dicio.io.input.CommandRecognitionPolicy

/**
 * Phase 4.3A — documents the **current** voice-to-action latency path.
 *
 * This object does **not** change runtime behavior. It exists so tests and
 * device logs can prove what the code actually waits for, versus what it
 * does not wait for.
 *
 * Observed CARFU device symptom (Phase 4.2 pass): after the user finishes
 * speaking, NAVIGATE / PLAY_MEDIA feel ~8–10s late. Candidates:
 *
 * A. Android SpeechRecognizer final-result latency
 * B. product 5s no-speech timeout
 * C. waiting for Android Final even when a COMPLETE command exists in partials
 * D. CommandSession / canonical lock
 * E. confirmation TTS sequencing
 * F. Android Intent execution
 * G. coroutine / dispatcher scheduling
 *
 * Source-proven answers (this policy + call sites):
 * - Execution **does** wait for Android Final ([waitsForAndroidFinalBeforeExecute]).
 * - Execution **does not** wait for the 5s product timeout after speech.
 * - Execution **does not** wait for confirmation TTS to finish before startActivity.
 * - Recognizer silence extras are **not** set (OEM defaults apply).
 */
object VoiceToActionLatencyPolicy {

    /** Product silence is a no-speech timeout only. After first speech/partial it is cancelled. */
    const val PRODUCT_NO_SPEECH_TIMEOUT_MS: Long = VoiceSessionManager.NO_SPEECH_TIMEOUT_MS

    fun waitsForAndroidFinalBeforeExecute(): Boolean = true

    fun executesFromCompletePartial(): Boolean = false

    fun productSilenceAppliesAfterSpeechDetected(): Boolean = false

    fun waitsForConfirmationTtsBeforeAndroidAction(): Boolean = false

    fun waitsForConfirmationTtsFinishBeforeAndroidAction(): Boolean = false

    fun setsRecognizerSilenceExtras(): Boolean = false

    fun maxSrRearms(): Int = CommandRecognitionPolicy.MAX_SR_REARMS

    fun tryFastPartialExecutionEnabled(): Boolean = false

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
