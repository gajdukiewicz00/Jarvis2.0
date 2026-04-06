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

    // ==================== System Control (migrated from legacy) ====================
    CLIPBOARD_COPY,
    CLIPBOARD_PASTE,
    UNDO_ACTION,
    SWITCH_WINDOW,
    CLOSE_WINDOW,
    FULLSCREEN,
    REFRESH_PAGE,
    NAVIGATE_BACK,
    NAVIGATE_FORWARD,
    SHOW_DESKTOP,
    OPEN_SETTINGS,
    SYSTEM_SEARCH,
    SWITCH_LANGUAGE,
    SCREENSHOT,
    SLEEP_MODE,
    MONITOR_OFF,

    // ==================== URL / Website Opening (migrated from legacy) ====================
    OPEN_URL,

    // ==================== Scenarios / Protocols ====================
    WORK_MODE,
    REST_MODE,
    FOCUS_MODE,
    PROTOCOL_HOUSE_PARTY,
    PROTOCOL_CLEAN_SLATE,
    /** Cozy evening — dim lights, ambient music */
    PROTOCOL_COZY_EVENING,
    /** Guests arriving — prepare environment */
    PROTOCOL_GUESTS,
    /** Holiday / New Year mood */
    PROTOCOL_HOLIDAY,
    /** Game mode — DND, close distractions */
    GAME_MODE,
    /** Morning routine */
    PROTOCOL_MORNING,
    /** Leaving home — secure and shut down */
    PROTOCOL_LEAVING,
    /** Panic / quick hide everything */
    PROTOCOL_PANIC,

    // ==================== Conversation / Personality (migrated from legacy) ====================
    HOW_ARE_YOU,
    WHAT_DOING,
    BORED,
    CHEER_UP,
    LOVE_RESPONSE,
    RANDOM_FACT,
    STANDBY_MODE,

    // ==================== Music (migrated from legacy) ====================
    PLAY_MUSIC,
    PLAY_RADIO,

    // ==================== Timer / Reminders ====================
    TIMER_SET,

    // ==================== Smart Home ====================
    SMART_HOME_TURN_ON,
    SMART_HOME_TURN_OFF,
    SMART_HOME_SET_VALUE,

    // ==================== Sarcasm / Personality ====================
    SARCASTIC_FAILURE,
    SECRET_PROJECT,
    SAFETY_BRIEFING,

    // ==================== Security ====================
    SECURITY_ALERT,

    // ==================== Life Context ====================
    LIFE_FOOD_TRACKER,
    LIFE_GUESTS,
    HEALTH_STRESS_HINT,

    // ==================== Fallback ====================
    UNKNOWN_COMMAND,
    STT_TIMEOUT,
    STT_NOISE,
    GOODBYE
}
