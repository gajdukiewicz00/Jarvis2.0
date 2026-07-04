package org.jarvis.media.tts;

/**
 * The voice used for dubbing. {@link #neutral()} is always safe. A USER_OWNED profile
 * is only constructable through {@link VoiceProfileFactory}, which enforces consent
 * and the server enablement flag.
 *
 * @param mode             NEUTRAL or USER_OWNED
 * @param voiceId          provider voice id (neutral default when blank)
 * @param consentConfirmed whether the user attested ownership/consent (USER_OWNED only)
 */
public record VoiceProfile(VoiceProfileMode mode, String voiceId, boolean consentConfirmed) {

    public static VoiceProfile neutral() {
        return new VoiceProfile(VoiceProfileMode.NEUTRAL, "ru-neutral", false);
    }
}
