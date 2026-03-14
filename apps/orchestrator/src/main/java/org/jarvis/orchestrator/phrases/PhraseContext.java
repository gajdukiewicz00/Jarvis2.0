package org.jarvis.orchestrator.phrases;

/**
 * Contexts / intents for Jarvis cinematic phrases.
 * Each context maps to specific Iron Man–style responses in multiple languages.
 */
public enum PhraseContext {
    // ==================== System / Greetings ====================
    /** Morning greeting with optional time, weather */
    MORNING_GREETING,
    /** System online and ready */
    SYSTEM_ONLINE,
    /** Welcome home (geolocation, smart lock) */
    WELCOME_HOME,
    /** Response when user says just "Jarvis" / "Джарвис" */
    WAKE_RESPONSE,
    /** Small talk - simple wake acknowledgment without action */
    SMALL_TALK_JARVIS,
    /** Response to "Are you there?" / "Ты тут?" / "Не спишь?" */
    ARE_YOU_THERE,
    /** Simple greeting - "hi jarvis" / "привет джарвис" */
    GREETING,
    /** Thank you response */
    THANKS,

    // ==================== Generic Acknowledgments ====================
    /** Action is starting */
    ACK_START,
    /** Action completed successfully */
    ACK_SUCCESS,
    /** Action failed */
    ACK_ERROR,
    /** Generic "at your service" */
    ACK_GENERIC,

    // ==================== Volume Control ====================
    VOLUME_UP,
    VOLUME_DOWN,
    VOLUME_MAX,
    MUTE,
    UNMUTE,

    // ==================== Media Control ====================
    PLAY,
    PAUSE,
    MEDIA_TOGGLE,
    NEXT_TRACK,
    PREVIOUS_TRACK,

    // ==================== App Control ====================
    OPEN_APP,
    OPEN_BROWSER,
    OPEN_YOUTUBE,
    OPEN_TERMINAL,
    OPEN_IDE,

    // ==================== Window Control ====================
    WINDOW_MINIMIZE,
    WINDOW_MAXIMIZE,
    LOCK_SCREEN,

    // ==================== Scenarios / Protocols ====================
    WORK_MODE,
    REST_MODE,
    FOCUS_MODE,
    PROTOCOL_HOUSE_PARTY,
    PROTOCOL_CLEAN_SLATE,

    // ==================== Timer / Reminders ====================
    TIMER_SET,

    // ==================== Smart Home ====================
    SMART_HOME_TURN_ON,
    SMART_HOME_TURN_OFF,
    SMART_HOME_SET_VALUE,

    // ==================== Sarcasm / Personality ====================
    /** Sarcasm after repeated failures */
    SARCASTIC_FAILURE,
    /** Working on something secret */
    SECRET_PROJECT,
    /** Safety briefing joke */
    SAFETY_BRIEFING,

    // ==================== Security ====================
    SECURITY_ALERT,

    // ==================== Life Context ====================
    /** Food tracker related */
    LIFE_FOOD_TRACKER,
    /** Guest reminder */
    LIFE_GUESTS,
    /** Stress/health hint (non-medical) */
    HEALTH_STRESS_HINT,

    // ==================== Fallback ====================
    UNKNOWN_COMMAND,
    /** STT timeout - couldn't hear the user */
    STT_TIMEOUT,
    /** STT noise - heard something but it was background noise */
    STT_NOISE,
    GOODBYE
}
