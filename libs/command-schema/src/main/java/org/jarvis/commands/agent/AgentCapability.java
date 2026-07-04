package org.jarvis.commands.agent;

/**
 * Phase 6 — capabilities reported by the Native Desktop Agent on startup
 * and refreshed in every heartbeat. The orchestrator and confirmation
 * flow consult these to know which intents the agent can actually perform
 * (e.g. don't dispatch a {@code SCREEN_CAPTURE} task to an agent that has
 * no display).
 */
public enum AgentCapability {
    VOICE_CAPTURE,        // microphone available
    TTS_PLAYBACK,         // speaker / TTS engine available
    WAKE_WORD,            // Porcupine / equivalent loaded
    SCREEN_CAPTURE,       // headed display + Robot/Toolkit OK
    WEBCAM,               // OpenCV VideoCapture available
    KEYBOARD_AUTOMATION,  // xdotool / equivalent
    MOUSE_AUTOMATION,     // xdotool / equivalent
    FILE_SYSTEM,          // host fs write rights for owner-side actions
    BROWSER_CONTROL,      // can drive the user's default browser
    IDE_CONTROL,          // can drive the configured IDE
    PC_POWER              // can issue shutdown / reboot / sleep
}
