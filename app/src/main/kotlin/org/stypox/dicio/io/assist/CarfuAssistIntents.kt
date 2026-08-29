package org.stypox.dicio.io.assist

import android.content.Intent
import android.os.Bundle
import org.stypox.dicio.io.session.CarfuActivationSource
import org.stypox.dicio.io.session.CarfuDiag

/**
 * FYT steering MODE is learned as the system Voice/Assistant function.
 * These are the official (and OEM-compatible) entry actions that must start
 * the existing [org.stypox.dicio.io.session.CommandSession], not a browser
 * search and not a second capture path.
 */
object CarfuAssistIntents {
    const val ACTION_ASSIST = Intent.ACTION_ASSIST
    const val ACTION_VOICE_COMMAND = Intent.ACTION_VOICE_COMMAND
    const val ACTION_VOICE_ASSIST = "android.intent.action.VOICE_ASSIST"
    const val ACTION_VOICE_SEARCH_HANDS_FREE = "android.speech.action.VOICE_SEARCH_HANDS_FREE"
    const val ACTION_SPEECH_WEB_SEARCH = "android.speech.action.WEB_SEARCH"
    const val ACTION_GENERIC_WEB_SEARCH = Intent.ACTION_WEB_SEARCH

    const val EXTRA_FROM_VOICE_INTERACTION = "org.stypox.dicio.FROM_VOICE_INTERACTION"
    const val EXTRA_SHOW_FLAGS = "org.stypox.dicio.VOICE_INTERACTION_SHOW_FLAGS"

    const val SETTINGS_VOICE_INPUT = "android.settings.VOICE_INPUT_SETTINGS"
    const val SETTINGS_MANAGE_DEFAULT_APPS = "android.settings.MANAGE_DEFAULT_APPS_SETTINGS"
    const val SETTINGS_SETTINGS = "android.settings.SETTINGS"

    fun isHardwareAssistAction(action: String?): Boolean = when (action) {
        ACTION_ASSIST,
        ACTION_VOICE_COMMAND,
        ACTION_VOICE_ASSIST,
        ACTION_VOICE_SEARCH_HANDS_FREE,
        ACTION_SPEECH_WEB_SEARCH -> true
        else -> false
    }

    /**
     * Generic [Intent.ACTION_WEB_SEARCH] is a browser search. Do not claim it as
     * DEFAULT, and do not treat it as a MODE press unless it arrived through
     * the VoiceInteractionService extra.
     */
    fun isGenericWebSearch(action: String?): Boolean = action == ACTION_GENERIC_WEB_SEARCH

    fun assistantSettingsActions(): List<String> = listOf(
        SETTINGS_VOICE_INPUT,
        SETTINGS_MANAGE_DEFAULT_APPS,
        SETTINGS_SETTINGS,
    )

    fun shouldExecuteRankerSkill(skillId: String, origin: CarfuActivationSource.Kind): Boolean {
        if (origin != CarfuActivationSource.Kind.HARDWARE_BUTTON) return true
        return skillId != SEARCH_SKILL_ID
    }

    fun isSensitiveExtraKey(key: String): Boolean {
        val folded = key.lowercase()
        return SENSITIVE_EXTRA_NEEDLES.any { folded.contains(it) }
    }

    fun summarizeSafeExtras(extras: Map<String, Any?>?): String {
        if (extras.isNullOrEmpty()) return "(none)"
        return extras.keys.sorted().joinToString(",") { key ->
            val value = extras[key]
            val type = value?.javaClass?.simpleName ?: "null"
            if (isSensitiveExtraKey(key)) {
                "$key:type=$type:redacted"
            } else {
                "$key:type=$type"
            }
        }
    }

    fun extrasAsMap(bundle: Bundle?): Map<String, Any?> {
        if (bundle == null || bundle.isEmpty) return emptyMap()
        val out = LinkedHashMap<String, Any?>()
        for (key in bundle.keySet()) {
            @Suppress("DEPRECATION")
            out[key] = bundle.get(key)
        }
        return out
    }

    fun summarizeIntent(intent: Intent?): String {
        if (intent == null) return "intent=null"
        val component = intent.component?.flattenToShortString() ?: "(none)"
        val categories = intent.categories?.sorted()?.joinToString(",") ?: "(none)"
        val extras = summarizeSafeExtras(extrasAsMap(intent.extras))
        return "action=${intent.action} component=$component categories=$categories " +
            "flags=0x${Integer.toHexString(intent.flags)} extras=[$extras]"
    }

    fun logIncoming(source: String, intent: Intent?) {
        CarfuDiag.assist("ASSIST_INTENT source=$source ${summarizeIntent(intent)}")
    }

    private const val SEARCH_SKILL_ID = "search"

    private val SENSITIVE_EXTRA_NEEDLES = listOf(
        "query",
        "text",
        "utterance",
        "result",
        "phrase",
        "transcript",
        "html",
        "recognized",
        "android.intent.extra",
        "android.speech.extra",
    )
}
