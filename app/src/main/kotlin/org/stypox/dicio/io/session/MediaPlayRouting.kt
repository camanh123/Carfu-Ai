package org.stypox.dicio.io.session

/**
 * Production media routing diagnostics. No song titles beyond the canonical query,
 * no tokens, no API keys.
 */
object MediaPlayRouting {
    const val UNSUPPORTED_SMARTTUBE_SPEECH_PREFIX = "Chưa hỗ trợ phát nhạc trên"

    @Volatile var smartTubeJackEntered: Int = 0
        private set
    @Volatile var legacyUnsupportedEntered: Int = 0
        private set
    @Volatile var lastExecutorSelected: String = ""
        private set
    @Volatile var lastSpokenResult: String = ""
        private set
    @Volatile var lastCanonicalCommand: String = ""
        private set
    @Volatile var lastMediaProvider: String = ""
        private set
    @Volatile var lastMediaQuery: String = ""
        private set

    fun resetForTests() {
        smartTubeJackEntered = 0
        legacyUnsupportedEntered = 0
        lastExecutorSelected = ""
        lastSpokenResult = ""
        lastCanonicalCommand = ""
        lastMediaProvider = ""
        lastMediaQuery = ""
    }

    fun onCanonicalPlayMedia(command: CanonicalCommand.PlayMedia) {
        lastCanonicalCommand = "PlayMedia"
        lastMediaProvider = command.provider.orEmpty()
        lastMediaQuery = command.query
        log(
            "CANONICAL_COMMAND=PlayMedia MEDIA_PROVIDER=${command.provider.orEmpty()} " +
                "MEDIA_QUERY=${command.query}",
        )
    }

    fun onExecutorSelected(executor: String) {
        lastExecutorSelected = executor
        log("EXECUTOR_SELECTED=$executor")
    }

    fun onSmartTubeJackEntered() {
        smartTubeJackEntered += 1
        lastExecutorSelected = "SmartTubeProductionJack"
        log("SMARTTUBE_JACK_ENTERED count=$smartTubeJackEntered")
    }

    fun onLegacyUnsupported(command: CanonicalCommand.PlayMedia, reason: String) {
        legacyUnsupportedEntered += 1
        lastExecutorSelected = "LegacyMediaUnsupported"
        log(
            "LEGACY_MEDIA_EXECUTOR_ENTERED count=$legacyUnsupportedEntered " +
                "MEDIA_PROVIDER=${command.provider.orEmpty()} reason=$reason",
        )
    }

    fun onSmartTubeResult(
        packageName: String?,
        action: String?,
        executionResult: String,
        spokenResult: String,
        resolverRequest: String?,
    ) {
        lastSpokenResult = spokenResult
        log(
            "RESOLVER_REQUEST=${resolverRequest.orEmpty()} " +
                "SMARTTUBE_PACKAGE=${packageName.orEmpty()} " +
                "ACTION_REQUEST=${action.orEmpty()} " +
                "EXECUTION_RESULT=$executionResult " +
                "SPOKEN_RESULT=$spokenResult",
        )
    }

    fun onSpoken(speech: String) {
        lastSpokenResult = speech
    }

    private fun log(message: String) {
        CarfuLog.i(VoiceLifecycleLog.TAG, "MEDIA_ROUTE $message")
        CarfuDiag.voice("MEDIA_ROUTE $message")
    }
}
