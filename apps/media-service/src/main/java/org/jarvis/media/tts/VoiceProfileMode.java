package org.jarvis.media.tts;

/**
 * Dubbing voice modes.
 *
 * <ul>
 *   <li>{@link #NEUTRAL} — a generic synthetic Russian voice (the default and the
 *       only mode that needs no consent). Does not imitate any real person.</li>
 *   <li>{@link #USER_OWNED} — a voice the user explicitly owns and has consented to
 *       use. Requires both {@code media.tts.allow-user-voice-profile=true} and a
 *       per-request consent flag. Exact cloning of a real actor's voice is never
 *       implemented and impersonation is never claimed.</li>
 * </ul>
 */
public enum VoiceProfileMode {
    NEUTRAL,
    USER_OWNED;

    public static VoiceProfileMode fromText(String value) {
        if (value == null || value.isBlank()) {
            return NEUTRAL;
        }
        return switch (value.trim().toLowerCase()) {
            case "user_owned", "user-owned", "userowned" -> USER_OWNED;
            case "neutral" -> NEUTRAL;
            default -> throw new IllegalArgumentException("Unknown voice profile mode: " + value);
        };
    }
}
