package org.jarvis.media.tts;

import org.jarvis.media.config.MediaProperties;
import org.springframework.stereotype.Component;

/**
 * Builds and authorizes {@link VoiceProfile}s. The ONLY place a USER_OWNED profile is
 * minted — and only when (a) the server enables user voice profiles and (b) the
 * request attests consent. This enforces the legal rule: no unauthorized voice
 * cloning, neutral voice by default.
 */
@Component
public class VoiceProfileFactory {

    private final MediaProperties props;

    public VoiceProfileFactory(MediaProperties props) {
        this.props = props;
    }

    public VoiceProfile resolve(String modeText, String voiceId, boolean consentConfirmed) {
        VoiceProfileMode mode = VoiceProfileMode.fromText(modeText);
        if (mode == VoiceProfileMode.NEUTRAL) {
            return VoiceProfile.neutral();
        }
        // USER_OWNED path — strictly gated.
        if (!props.tts().allowUserVoiceProfile()) {
            throw new IllegalArgumentException(
                    "User-owned voice profiles are disabled (media.tts.allow-user-voice-profile=false)");
        }
        if (!consentConfirmed) {
            throw new IllegalArgumentException(
                    "User-owned voice requires explicit consent that the voice is owned by the user");
        }
        if (voiceId == null || voiceId.isBlank()) {
            throw new IllegalArgumentException("User-owned voice requires a voiceId");
        }
        return new VoiceProfile(VoiceProfileMode.USER_OWNED, voiceId.trim(), true);
    }
}
