package org.stypox.dicio.aliasdiagnostic

/**
 * Hard policy for the diagnostic APK. Not production Voice.
 *
 * This app listens, transcribes, normalizes, and displays text.
 * It must not execute commands, launch media/nav apps, own production
 * services, become the default assistant, or use Accessibility / wake.
 */
object AliasDiagnosticPolicy {
    const val APPLICATION_ID = "org.stypox.dicio.aliasdiagnostic"
    const val SPEECH_LOCALE = "vi-VN"
    const val PRODUCTION_WIRED = "NO"
    const val EXECUTES_COMMANDS = false
    const val LAUNCHES_SMARTTUBE = false
    const val LAUNCHES_YOUTUBE = false
    const val LAUNCHES_MAPS = false
    const val CALLS_PRODUCTION_NLU = false
    const val USES_ACCESSIBILITY = false
    const val BECOMES_DEFAULT_ASSISTANT = false
    const val USES_BACKGROUND_WAKE = false
}
