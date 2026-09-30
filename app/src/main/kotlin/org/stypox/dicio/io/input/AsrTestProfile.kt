package org.stypox.dicio.io.input

import android.content.Context
import android.content.Intent
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import org.stypox.dicio.io.session.CommandSessionPhase

/**
 * Diagnostic A/B ear for the real VIA [android.speech.SpeechRecognizer] path.
 *
 * A is the current VIA RecognizerIntent, unchanged.
 * B is only the public configuration proven in SmartTube
 * `leanback-1.0.0/.../widget/SearchBar.startRecognition()`:
 * [android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH],
 * [android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL] =
 * [android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM],
 * [android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS] = true.
 * SmartTube does not put [android.speech.RecognizerIntent.EXTRA_LANGUAGE].
 *
 * Both profiles hand the same [InputEvent] to the existing SkillEvaluator.
 * Nothing downstream may branch on the profile.
 */
enum class AsrTestProfile {
    A_VIA_CURRENT,
    B_SMARTTUBE_STYLE,
    ;

    val persistedValue: String get() = name

    val displayLabel: String
        get() = when (this) {
            A_VIA_CURRENT -> "A — VIA Current"
            B_SMARTTUBE_STYLE -> "B — SmartTube Style"
        }

    fun toggled(): AsrTestProfile = when (this) {
        A_VIA_CURRENT -> B_SMARTTUBE_STYLE
        B_SMARTTUBE_STYLE -> A_VIA_CURRENT
    }

    companion object {
        val DEFAULT = A_VIA_CURRENT

        fun fromPersisted(raw: String?): AsrTestProfile = when (raw) {
            B_SMARTTUBE_STYLE.persistedValue -> B_SMARTTUBE_STYLE
            else -> DEFAULT
        }
    }
}

sealed class AsrIntentExtra {
    abstract val key: String

    data class Str(override val key: String, val value: String) : AsrIntentExtra()
    data class Bool(override val key: String, val value: Boolean) : AsrIntentExtra()
    data class IntVal(override val key: String, val value: Int) : AsrIntentExtra()
}

data class AsrIntentSpec(
    val action: String,
    val extras: List<AsrIntentExtra>,
) {
    fun forcesExplicitLanguage(): Boolean = extras.any { extra ->
        extra.key == AsrRecognizerIntentProfiles.EXTRA_LANGUAGE ||
            extra.key == AsrRecognizerIntentProfiles.EXTRA_LANGUAGE_PREFERENCE
    }

    fun explicitLanguage(): String? = extras.firstNotNullOfOrNull { extra ->
        if (extra is AsrIntentExtra.Str &&
            extra.key == AsrRecognizerIntentProfiles.EXTRA_LANGUAGE
        ) {
            extra.value
        } else {
            null
        }
    }
}

object AsrRecognizerIntentProfiles {
    const val DOWNSTREAM_CONSUMER = "org.stypox.dicio.eval.SkillEvaluator"

    const val EXTRA_LANGUAGE_MODEL = "android.speech.extra.LANGUAGE_MODEL"
    const val EXTRA_LANGUAGE = "android.speech.extra.LANGUAGE"
    const val EXTRA_LANGUAGE_PREFERENCE = "android.speech.extra.LANGUAGE_PREFERENCE"
    const val EXTRA_PARTIAL_RESULTS = "android.speech.extra.PARTIAL_RESULTS"
    const val EXTRA_MAX_RESULTS = "android.speech.extra.MAX_RESULTS"
    const val EXTRA_PREFER_OFFLINE = "android.speech.extra.PREFER_OFFLINE"
    const val EXTRA_CALLING_PACKAGE = "calling_package"

    /**
     * Same pipeline after the recognizer, for every profile.
     * Profile selection must not change this list.
     */
    fun downstreamPipeline(@Suppress("UNUSED_PARAMETER") profile: AsrTestProfile): List<String> =
        listOf(
            "SpeechRecognizer",
            "RAW_PARTIAL",
            "RAW_FINAL",
            "StableCompletePartialPolicy",
            DOWNSTREAM_CONSUMER,
            "CommandTranscriptNormalizer",
            "VietnameseCommandUnderstanding",
            "CanonicalCommand",
            "CanonicalCommandExecutor",
        )

    fun downstreamConsumer(@Suppress("UNUSED_PARAMETER") profile: AsrTestProfile): String =
        DOWNSTREAM_CONSUMER

    fun specFor(profile: AsrTestProfile, callingPackage: String): AsrIntentSpec = when (profile) {
        AsrTestProfile.A_VIA_CURRENT -> viaCurrent(callingPackage)
        AsrTestProfile.B_SMARTTUBE_STYLE -> smartTubeStyle()
    }

    fun describe(spec: AsrIntentSpec): String = buildString {
        append("action=").append(spec.action)
        spec.extras.forEach { extra ->
            append(' ')
            append(extra.key)
            append('=')
            when (extra) {
                is AsrIntentExtra.Str -> append(extra.value)
                is AsrIntentExtra.Bool -> append(extra.value)
                is AsrIntentExtra.IntVal -> append(extra.value)
            }
        }
    }

    fun toIntent(spec: AsrIntentSpec): Intent = Intent(spec.action).apply {
        spec.extras.forEach { extra ->
            when (extra) {
                is AsrIntentExtra.Str -> putExtra(extra.key, extra.value)
                is AsrIntentExtra.Bool -> putExtra(extra.key, extra.value)
                is AsrIntentExtra.IntVal -> putExtra(extra.key, extra.value)
            }
        }
    }

    /**
     * Control. This is the recognizer intent VIA already builds from
     * [CommandRecognitionPolicy.recognizerIntentConfig]. No extras are added
     * or removed relative to that config.
     */
    private fun viaCurrent(callingPackage: String): AsrIntentSpec {
        val cfg = CommandRecognitionPolicy.recognizerIntentConfig()
        return AsrIntentSpec(
            action = cfg.action,
            extras = listOf(
                AsrIntentExtra.Str(EXTRA_LANGUAGE_MODEL, cfg.languageModel),
                AsrIntentExtra.Str(EXTRA_LANGUAGE, cfg.language),
                AsrIntentExtra.Str(EXTRA_LANGUAGE_PREFERENCE, cfg.language),
                AsrIntentExtra.Bool(EXTRA_PARTIAL_RESULTS, cfg.partialResults),
                AsrIntentExtra.IntVal(EXTRA_MAX_RESULTS, cfg.maxResults),
                AsrIntentExtra.Bool(EXTRA_PREFER_OFFLINE, cfg.preferOffline),
                AsrIntentExtra.Str(EXTRA_CALLING_PACKAGE, callingPackage),
            ),
        )
    }

    /**
     * SmartTube `SearchBar.startRecognition` public API only.
     * Does not set EXTRA_LANGUAGE, so the system recognizer keeps its own
     * locale decision.
     */
    private fun smartTubeStyle(): AsrIntentSpec = AsrIntentSpec(
        action = CommandRecognitionPolicy.RECOGNIZER_INTENT_ACTION,
        extras = listOf(
            AsrIntentExtra.Str(
                EXTRA_LANGUAGE_MODEL,
                CommandRecognitionPolicy.LANGUAGE_MODEL_FREE_FORM,
            ),
            AsrIntentExtra.Bool(EXTRA_PARTIAL_RESULTS, true),
        ),
    )
}

object AsrProfileSessionRules {
    fun canSwitch(
        recognizerOwnsMicrophone: Boolean,
        productPhase: CommandSessionPhase,
    ): Boolean = !recognizerOwnsMicrophone &&
        productPhase == CommandSessionPhase.IDLE_WAKE

    /** A start retires any previous recognizer before create, so occupancy stays 1. */
    fun occupancyAfterRetiredArm(): Int = 1

    fun allowsConcurrentRecognizers(): Boolean = false
}

/**
 * Process-wide microphone lease for the single [android.speech.SpeechRecognizer].
 * [markOwned] is idempotent: a second arm still reports one owner.
 */
object AsrRecognizerLease {
    private val owned = AtomicInteger(0)

    fun markOwned() {
        owned.set(1)
    }

    fun markIdle() {
        owned.set(0)
    }

    fun ownsMicrophone(): Boolean = owned.get() == 1

    fun activeCount(): Int = owned.get()

    fun resetForTests() {
        owned.set(0)
    }
}

/** Mirrors [org.stypox.dicio.io.session.CommandSession] phase for the idle switch gate. */
object AsrProductPhaseMirror {
    @Volatile
    var phase: CommandSessionPhase = CommandSessionPhase.IDLE_WAKE
        private set

    fun publish(next: CommandSessionPhase) {
        phase = next
    }

    fun resetForTests() {
        phase = CommandSessionPhase.IDLE_WAKE
    }
}

interface AsrProfilePersistence {
    fun readRaw(): String?
    fun writeRaw(value: String)
}

object AsrProfileSelection {
    fun read(store: AsrProfilePersistence): AsrTestProfile =
        AsrTestProfile.fromPersisted(store.readRaw())

    /**
     * Writes [profile] only while the recognizer is idle and the product
     * session is [CommandSessionPhase.IDLE_WAKE]. Both the UI phase and the
     * mirrored product phase must be idle.
     */
    fun trySelect(
        profile: AsrTestProfile,
        uiPhase: CommandSessionPhase,
        mirroredPhase: CommandSessionPhase,
        recognizerOwnsMicrophone: Boolean,
        store: AsrProfilePersistence,
    ): Boolean {
        if (!AsrProfileSessionRules.canSwitch(recognizerOwnsMicrophone, uiPhase)) return false
        if (!AsrProfileSessionRules.canSwitch(false, mirroredPhase)) return false
        store.writeRaw(profile.persistedValue)
        return true
    }
}

object AsrTestProfileState {
    val profile = MutableStateFlow(AsrTestProfile.DEFAULT)
}

private const val ASR_PROFILE_PREFS = "carfu_asr_test_profile"
private const val ASR_PROFILE_KEY = "profile"

class SharedPrefsAsrProfilePersistence(
    private val context: Context,
) : AsrProfilePersistence {
    override fun readRaw(): String? =
        context.getSharedPreferences(ASR_PROFILE_PREFS, Context.MODE_PRIVATE)
            .getString(ASR_PROFILE_KEY, null)

    override fun writeRaw(value: String) {
        context.getSharedPreferences(ASR_PROFILE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(ASR_PROFILE_KEY, value)
            .commit()
    }
}

object AsrTestProfileController {
    fun ensureLoaded(context: Context) {
        AsrTestProfileState.profile.value =
            AsrProfileSelection.read(SharedPrefsAsrProfilePersistence(context))
    }

    fun current(context: Context): AsrTestProfile {
        ensureLoaded(context)
        return AsrTestProfileState.profile.value
    }

    fun trySelect(
        context: Context,
        profile: AsrTestProfile,
        uiPhase: CommandSessionPhase = AsrProductPhaseMirror.phase,
    ): Boolean {
        val ok = AsrProfileSelection.trySelect(
            profile = profile,
            uiPhase = uiPhase,
            mirroredPhase = AsrProductPhaseMirror.phase,
            recognizerOwnsMicrophone = AsrRecognizerLease.ownsMicrophone(),
            store = SharedPrefsAsrProfilePersistence(context),
        )
        if (ok) {
            AsrTestProfileState.profile.value = profile
        }
        return ok
    }
}
