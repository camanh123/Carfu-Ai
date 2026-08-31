package org.stypox.dicio.io.input

import org.stypox.dicio.settings.datastore.BackgroundWake
import org.stypox.dicio.settings.datastore.CommandRecognitionEngine
import org.stypox.dicio.settings.datastore.UserSettings

/**
 * JVM-testable decisions for CARFU command STT.
 *
 * Android/Google [android.speech.SpeechRecognizer] is the primary Vietnamese engine.
 * Vosk is an explicit legacy/offline fallback, never a silent substitute.
 */
object CommandRecognitionPolicy {
    const val ANDROID_ECHO_GUARD_MS = 300L
    const val MODE_TTS_START_BUDGET_MS = 500L
    const val ANDROID_LISTEN_TIMEOUT_MS = 12_000L
    const val ANDROID_MODEL_PATH = "android-speech-vi-VN"

    const val RECOGNIZER_INTENT_ACTION = "android.speech.action.RECOGNIZE_SPEECH"
    const val LANGUAGE_MODEL_FREE_FORM = "free_form"
    const val LANGUAGE_VI_VN = "vi-VN"
    const val MAX_RESULTS = 3
    const val PREFER_OFFLINE = false
    const val PARTIAL_RESULTS = true

    data class RecognitionServiceCandidate(
        val packageName: String,
        val className: String,
    )

    data class AndroidRecognizerIntentConfig(
        val action: String = RECOGNIZER_INTENT_ACTION,
        val languageModel: String = LANGUAGE_MODEL_FREE_FORM,
        val language: String = LANGUAGE_VI_VN,
        val partialResults: Boolean = PARTIAL_RESULTS,
        val maxResults: Int = MAX_RESULTS,
        val preferOffline: Boolean = PREFER_OFFLINE,
    )

    enum class RecognizerTerminal {
        RESULT,
        ERROR,
        TIMEOUT,
    }

    fun resolveEngine(settings: UserSettings): CommandRecognitionEngine =
        resolveEngine(settings.commandRecognitionEngine)

    fun resolveEngine(value: CommandRecognitionEngine): CommandRecognitionEngine = when (value) {
        CommandRecognitionEngine.COMMAND_RECOGNITION_ENGINE_VOSK_LEGACY ->
            CommandRecognitionEngine.COMMAND_RECOGNITION_ENGINE_VOSK_LEGACY
        CommandRecognitionEngine.COMMAND_RECOGNITION_ENGINE_ANDROID_ONLINE,
        CommandRecognitionEngine.COMMAND_RECOGNITION_ENGINE_UNSET,
        CommandRecognitionEngine.UNRECOGNIZED ->
            CommandRecognitionEngine.COMMAND_RECOGNITION_ENGINE_ANDROID_ONLINE
    }

    fun isAndroidOnline(settings: UserSettings): Boolean =
        resolveEngine(settings) ==
            CommandRecognitionEngine.COMMAND_RECOGNITION_ENGINE_ANDROID_ONLINE

    fun isAndroidOnline(value: CommandRecognitionEngine): Boolean =
        resolveEngine(value) ==
            CommandRecognitionEngine.COMMAND_RECOGNITION_ENGINE_ANDROID_ONLINE

    fun shouldConstructVosk(engine: CommandRecognitionEngine): Boolean =
        resolveEngine(engine) ==
            CommandRecognitionEngine.COMMAND_RECOGNITION_ENGINE_VOSK_LEGACY

    fun shouldInitializeVoskAtStartup(engine: CommandRecognitionEngine): Boolean =
        shouldConstructVosk(engine)

    fun needsAcceptanceProfileMigration(settings: UserSettings): Boolean =
        settings.commandRecognitionEngine ==
            CommandRecognitionEngine.COMMAND_RECOGNITION_ENGINE_UNSET ||
            settings.commandRecognitionEngine == CommandRecognitionEngine.UNRECOGNIZED

    fun applyAcceptanceProfile(settings: UserSettings): UserSettings =
        settings.toBuilder()
            .setCommandRecognitionEngine(
                CommandRecognitionEngine.COMMAND_RECOGNITION_ENGINE_ANDROID_ONLINE,
            )
            .setBackgroundWake(BackgroundWake.BACKGROUND_WAKE_DISABLED)
            .build()

    fun recognizerIntentConfig(): AndroidRecognizerIntentConfig = AndroidRecognizerIntentConfig()

    fun shouldLaunchRecognizerActivity(): Boolean = false

    fun shouldLaunchBrowserSearch(): Boolean = false

    fun canStartAndroidRecognizer(hubRecording: Boolean): Boolean = !hubRecording

    fun microphoneOwnersOverlap(
        hubRecording: Boolean,
        speechRecognizerActive: Boolean,
    ): Boolean = hubRecording && speechRecognizerActive

    fun shouldDestroyRecognizerOn(event: RecognizerTerminal): Boolean = true

    fun modeTtsStartWithinBudget(intentReceivedMs: Long, ttsOnStartMs: Long): Boolean {
        if (intentReceivedMs <= 0L || ttsOnStartMs < intentReceivedMs) return false
        return ttsOnStartMs - intentReceivedMs <= MODE_TTS_START_BUDGET_MS
    }

    fun echoGuardMs(androidOnline: Boolean): Long =
        if (androidOnline) ANDROID_ECHO_GUARD_MS else 450L

    fun preferGoogleRecognitionService(packageName: String): Boolean {
        val pkg = packageName.lowercase()
        return pkg.contains("google") || pkg.contains("gms")
    }

    /**
     * Never bind CARFU's own [org.stypox.dicio.io.input.stt_service.SttService].
     * Prefer a Google recognizer when several external services exist.
     */
    fun pickExternalRecognitionService(
        selfPackage: String,
        candidates: List<RecognitionServiceCandidate>,
    ): RecognitionServiceCandidate? {
        val external = candidates.filter { it.packageName != selfPackage }
        if (external.isEmpty()) return null
        return external.firstOrNull { preferGoogleRecognitionService(it.packageName) }
            ?: external.first()
    }

    fun finalUtterances(
        results: List<String>,
        confidences: List<Float>?,
    ): List<Pair<String, Float>> {
        val texts = results.map { it.trim() }.filter { it.isNotEmpty() }
        if (texts.isEmpty()) return emptyList()
        if (confidences != null && confidences.size == texts.size) {
            return texts.zip(confidences)
        }
        return texts.map { it to 1.0f }
    }

    fun isNoSpeechError(code: Int): Boolean = when (code) {
        6, // ERROR_SPEECH_TIMEOUT
        7, // ERROR_NO_MATCH
        -> true
        else -> false
    }
}
