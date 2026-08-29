package org.stypox.dicio.io.session

/**
 * Routes the single physical 16 kHz PCM stream. Wake scoring and command recognition
 * never own a second [android.media.AudioRecord]; they only subscribe to this route.
 */
enum class CarfuPcmRoute {
    OPEN_WAKE_WORD,
    COMMAND_RECOGNIZER,
    DISCARD,
}

object CarfuPcmRouter {
    fun route(
        phase: CommandSessionPhase,
        cooldownActive: Boolean = false,
        interactionPaused: Boolean = false,
        backgroundWakeEnabled: Boolean = true,
    ): CarfuPcmRoute {
        if (phase == CommandSessionPhase.COMMAND_LISTENING) {
            return CarfuPcmRoute.COMMAND_RECOGNIZER
        }
        if (!backgroundWakeEnabled &&
            (phase == CommandSessionPhase.IDLE_WAKE ||
                phase == CommandSessionPhase.RETURNING_TO_WAKE)
        ) {
            return CarfuPcmRoute.DISCARD
        }
        if (cooldownActive || interactionPaused) {
            return CarfuPcmRoute.DISCARD
        }
        return when (phase) {
            CommandSessionPhase.IDLE_WAKE,
            CommandSessionPhase.RETURNING_TO_WAKE -> CarfuPcmRoute.OPEN_WAKE_WORD
            CommandSessionPhase.WAKE_DETECTED,
            CommandSessionPhase.ACKNOWLEDGING,
            CommandSessionPhase.PROCESSING,
            CommandSessionPhase.RESPONDING -> CarfuPcmRoute.DISCARD
            CommandSessionPhase.COMMAND_LISTENING -> CarfuPcmRoute.COMMAND_RECOGNIZER
        }
    }

    fun shouldResetWakeDetectors(previous: CarfuPcmRoute, next: CarfuPcmRoute): Boolean =
        previous != next

    fun shouldResetRecognizer(previous: CarfuPcmRoute, next: CarfuPcmRoute): Boolean =
        previous != next && (
            previous == CarfuPcmRoute.COMMAND_RECOGNIZER ||
                next == CarfuPcmRoute.COMMAND_RECOGNIZER
            )
}
