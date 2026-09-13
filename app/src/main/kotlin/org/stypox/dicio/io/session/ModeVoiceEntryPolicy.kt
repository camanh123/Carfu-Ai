package org.stypox.dicio.io.session

/**
 * MODE / UI voice-entry decisions.
 *
 * A deliberate MODE/UI press creates one [VoiceSession] when RECORD_AUDIO is
 * granted and SpeechRecognizer is available. A transient ConnectivityManager
 * "offline" snapshot must not abandon that trigger. Internet recovery must
 * never open a later session by itself.
 */
object ModeVoiceEntryPolicy {
    fun mayCreateVoiceSession(
        recordAudioGranted: Boolean,
        recognizerAvailable: Boolean,
    ): Boolean = recordAudioGranted && recognizerAvailable

    /**
     * ConnectivityManager activeNetwork / INTERNET capability is advisory only
     * for MODE/UI. Automotive stacks can report unusable internet while Google
     * SpeechRecognizer still works.
     */
    @Suppress("UNUSED_PARAMETER")
    fun connectivitySnapshotBlocksDeliberateMode(usableInternet: Boolean): Boolean = false

    fun autoRetryOnNetworkRecovery(): Boolean = false

    /**
     * Granted RECORD_AUDIO is not a SpeechRecognizer refusal. Return a reason
     * only on a real permission refusal branch.
     */
    fun srRefusedReasonForPermission(
        granted: Boolean,
        runtimeLabel: String,
    ): String? = if (granted) null else runtimeLabel
}
