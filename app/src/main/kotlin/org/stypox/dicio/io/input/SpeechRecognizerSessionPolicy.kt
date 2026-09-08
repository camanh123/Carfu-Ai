package org.stypox.dicio.io.input

/**
 * Phase 4.2: SpeechRecognizer callbacks are events. They must not independently
 * own the CARFU product session lifecycle.
 *
 * One MIC/MODE trigger = one product session. The ~5s product silence timeout
 * owns no-speech. Transient / early recognizer callbacks are absorbed unless
 * they are truly unrecoverable. MAX_SR_REARMS remains 0 — absorbing an error
 * does not restart SpeechRecognizer.
 */
object SpeechRecognizerSessionPolicy {
    /** android.speech.SpeechRecognizer error constants (kept numeric for JVM tests). */
    const val ERROR_NETWORK_TIMEOUT = 1
    const val ERROR_NETWORK = 2
    const val ERROR_AUDIO = 3
    const val ERROR_SERVER = 4
    const val ERROR_CLIENT = 5
    const val ERROR_SPEECH_TIMEOUT = 6
    const val ERROR_NO_MATCH = 7
    const val ERROR_RECOGNIZER_BUSY = 8
    const val ERROR_INSUFFICIENT_PERMISSIONS = 9
    const val ERROR_TOO_MANY_REQUESTS = 10
    const val ERROR_SERVER_DISCONNECTED = 11
    const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
    const val ERROR_LANGUAGE_UNAVAILABLE = 13
    const val ERROR_CANNOT_CONNECT_TO_SERVER = 14

    enum class ProductAction {
        /** Wrong recognizer generation — ignore completely. */
        IGNORE_STALE,
        /**
         * Recognizer ended; destroy that SR instance; keep the product session.
         * Product silence timeout remains the no-speech owner. No re-arm.
         */
        KEEP_PRODUCT_SESSION,
        /** Emit [InputEvent.None] so rescue / silent terminal can run. */
        TERMINATE_NO_SPEECH,
        /** Emit [InputEvent.Error] — permission / audio / language hard failure. */
        TERMINATE_UNRECOVERABLE,
        /** Non-empty finals — emit [InputEvent.Final]. */
        PROCESS_FINAL,
    }

    fun errorName(code: Int): String = when (code) {
        ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
        ERROR_NETWORK -> "ERROR_NETWORK"
        ERROR_AUDIO -> "ERROR_AUDIO"
        ERROR_SERVER -> "ERROR_SERVER"
        ERROR_CLIENT -> "ERROR_CLIENT"
        ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
        ERROR_NO_MATCH -> "ERROR_NO_MATCH"
        ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
        ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
        ERROR_TOO_MANY_REQUESTS -> "ERROR_TOO_MANY_REQUESTS"
        ERROR_SERVER_DISCONNECTED -> "ERROR_SERVER_DISCONNECTED"
        ERROR_LANGUAGE_NOT_SUPPORTED -> "ERROR_LANGUAGE_NOT_SUPPORTED"
        ERROR_LANGUAGE_UNAVAILABLE -> "ERROR_LANGUAGE_UNAVAILABLE"
        ERROR_CANNOT_CONNECT_TO_SERVER -> "ERROR_CANNOT_CONNECT_TO_SERVER"
        else -> "ERROR_UNKNOWN"
    }

    fun isUnrecoverable(code: Int): Boolean = when (code) {
        ERROR_INSUFFICIENT_PERMISSIONS,
        ERROR_AUDIO,
        ERROR_LANGUAGE_NOT_SUPPORTED,
        ERROR_LANGUAGE_UNAVAILABLE,
        -> true
        else -> false
    }

    fun isTransientRecognizerEnd(code: Int): Boolean = when (code) {
        ERROR_NO_MATCH,
        ERROR_SPEECH_TIMEOUT,
        ERROR_CLIENT,
        ERROR_RECOGNIZER_BUSY,
        ERROR_NETWORK,
        ERROR_NETWORK_TIMEOUT,
        ERROR_SERVER,
        ERROR_SERVER_DISCONNECTED,
        ERROR_TOO_MANY_REQUESTS,
        ERROR_CANNOT_CONNECT_TO_SERVER,
        -> true
        else -> false
    }

    /**
     * Enter COMMAND_LISTENING (and request audio focus) before [SpeechRecognizer.startListening]
     * so the listening cue cannot start a MediaPlayer against a live recognizer, and so CARFU
     * does not request a second audio-focus grant after the recognizer has already started.
     */
    fun markCommandListeningBeforeStartListening(): Boolean = true

    /** End-of-speech is acoustic only — never a product terminal. */
    fun endOfSpeechTerminatesProduct(): Boolean = false

    fun onError(
        code: Int,
        generationMatches: Boolean,
        sawReady: Boolean,
        sawSpeechOrPartial: Boolean,
        elapsedMs: Long,
        productTimeoutMs: Long,
    ): ProductAction {
        if (!generationMatches) return ProductAction.IGNORE_STALE
        if (isUnrecoverable(code)) return ProductAction.TERMINATE_UNRECOVERABLE
        if (elapsedMs >= productTimeoutMs) return ProductAction.TERMINATE_NO_SPEECH
        if (sawSpeechOrPartial && CommandRecognitionPolicy.isNoSpeechError(code)) {
            return ProductAction.TERMINATE_NO_SPEECH
        }
        if (isTransientRecognizerEnd(code)) return ProductAction.KEEP_PRODUCT_SESSION
        if (code == ERROR_CLIENT && !sawReady) return ProductAction.KEEP_PRODUCT_SESSION
        return ProductAction.KEEP_PRODUCT_SESSION
    }

    fun onResults(
        utteranceCount: Int,
        generationMatches: Boolean,
        sawSpeechOrPartial: Boolean,
        elapsedMs: Long,
        productTimeoutMs: Long,
    ): ProductAction {
        if (!generationMatches) return ProductAction.IGNORE_STALE
        if (utteranceCount > 0) return ProductAction.PROCESS_FINAL
        if (elapsedMs >= productTimeoutMs) return ProductAction.TERMINATE_NO_SPEECH
        if (sawSpeechOrPartial) return ProductAction.TERMINATE_NO_SPEECH
        return ProductAction.KEEP_PRODUCT_SESSION
    }

    fun onPartial(generationMatches: Boolean, textBlank: Boolean): Boolean {
        if (!generationMatches) return false
        return !textBlank
    }
}
