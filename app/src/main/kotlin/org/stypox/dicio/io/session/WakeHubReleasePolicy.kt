package org.stypox.dicio.io.session

/**
 * Bounded handoff from WakeService / [CarfuPcmHub] AudioRecord to Android
 * SpeechRecognizer. Never start SR while the hub is still recording, and never
 * spin a retry loop if release does not complete.
 */
object WakeHubReleasePolicy {
    /** Minimum wait that still covers an async AudioRecord stop/release. */
    const val MAX_WAIT_MS = 250L
    const val POLL_MS = 25L
    const val MAX_POLLS = 10
    const val REFUSE_REASON = "hub_not_released"

    enum class Decision {
        START_SPEECH_RECOGNIZER,
        WAIT,
        REFUSE_STILL_RECORDING,
    }

    fun decision(hubRecording: Boolean, elapsedMs: Long): Decision = when {
        !hubRecording -> Decision.START_SPEECH_RECOGNIZER
        elapsedMs >= MAX_WAIT_MS -> Decision.REFUSE_STILL_RECORDING
        else -> Decision.WAIT
    }

    fun shouldStartSpeechRecognizer(hubRecording: Boolean): Boolean = !hubRecording

    fun timedOut(elapsedMs: Long): Boolean = elapsedMs >= MAX_WAIT_MS
}
