package org.stypox.dicio.io.session

/**
 * Single-owner microphone / command-session states for the automotive wake→STT flow.
 * Only one phase is active at a time; the single PCM stream is routed to either
 * OpenWakeWord or the Vosk recognizer, never both, and never via a second AudioRecord.
 */
enum class CommandSessionPhase {
    IDLE_WAKE,
    WAKE_DETECTED,
    ACKNOWLEDGING,
    COMMAND_LISTENING,
    PROCESSING,
    RESPONDING,
    RETURNING_TO_WAKE,
}

enum class CommandSessionEvent {
    WAKE_DETECTED,
    TTS_STARTED,
    TTS_COMPLETED,
    COMMAND_AUDIO_STARTED,
    SPEECH_BEGIN,
    FINAL_TEXT,
    INTENT_MATCH,
    SESSION_END,
    TIMEOUT,
    ERROR,
    REJECT_NOISE,
}
