package org.jarvis.desktop.model

/**
 * State of the voice assistant - implements a proper state machine.
 * 
 * State transitions:
 * - IDLE → LISTENING (on wake word detection)
 * - LISTENING → PROCESSING (on final transcript received)
 * - PROCESSING → TTS_PLAYBACK (on response with TTS audio)
 * - TTS_PLAYBACK → COOLDOWN (on TTS playback finished)
 * - COOLDOWN → IDLE (after cooldown timer expires)
 * 
 * Wake word and mic are DISABLED during TTS_PLAYBACK and COOLDOWN to prevent
 * background noise (TV, etc.) from triggering phantom commands.
 */
enum class VoiceState {
    /** Idle - not listening, wake word detection active */
    IDLE,
    
    /** Listening for wake word "Jarvis" (wake word engine active, mic NOT streaming) */
    LISTENING_WAKE_WORD,
    
    /** Recording user command - mic streaming audio to server */
    LISTENING,
    
    /** Final transcript received, waiting for intent/action/TTS response */
    PROCESSING,
    
    /** Playing TTS response - mic MUTED, wake word DISABLED */
    TTS_PLAYBACK,
    
    /** Cooldown after TTS - mic NOT streaming, wake word DISABLED */
    COOLDOWN,
    
    /** Error state */
    ERROR;
    
    fun getDisplayText(): String = when (this) {
        IDLE -> "Не активен"
        LISTENING_WAKE_WORD -> "Скажите 'Jarvis'"
        LISTENING -> "Слушаю команду..."
        PROCESSING -> "Обрабатываю..."
        TTS_PLAYBACK -> "Джарвис отвечает..."
        COOLDOWN -> "Пауза..."
        ERROR -> "Ошибка"
    }
    
    fun getColorHex(): String = when (this) {
        IDLE -> "#808080"  // Gray
        LISTENING_WAKE_WORD -> "#4A90E2"  // Blue
        LISTENING -> "#E74C3C"  // Red (recording)
        PROCESSING -> "#F39C12"  // Yellow/Orange
        TTS_PLAYBACK -> "#27AE60"  // Green
        COOLDOWN -> "#9B59B6"  // Purple
        ERROR -> "#C0392B"  // Dark red
    }
    
    /** Returns true if wake word detection should be disabled in this state */
    fun isWakeWordDisabled(): Boolean = this in listOf(LISTENING, PROCESSING, TTS_PLAYBACK, COOLDOWN)
    
    /** Returns true if mic should be muted (not sending audio) in this state */
    fun isMicMuted(): Boolean = this in listOf(TTS_PLAYBACK, COOLDOWN, IDLE)
}
