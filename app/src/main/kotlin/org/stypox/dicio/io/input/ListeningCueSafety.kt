package org.stypox.dicio.io.input

import org.stypox.dicio.io.session.CommandSessionPhase

/**
 * Phase 4.1: listening cue playback must never crash the process.
 *
 * [android.media.MediaPlayer.create] may return null on automotive ROMs; calling
 * methods on that result from a background dispatcher previously NPE'd at voice start.
 */
object ListeningCueSafety {
    fun shouldPlayListeningCue(
        becameListening: Boolean,
        phase: CommandSessionPhase,
    ): Boolean {
        if (!becameListening) return false
        return phase != CommandSessionPhase.COMMAND_LISTENING &&
            phase != CommandSessionPhase.ACKNOWLEDGING
    }

    /** Null [MediaPlayer] from create() is a soft skip — never throw. */
    fun isCreatedPlayerUsable(player: Any?): Boolean = player != null
}
