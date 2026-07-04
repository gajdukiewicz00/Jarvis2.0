package org.jarvis.media.tts;

import org.jarvis.media.support.MediaTestFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VoiceProfileFactoryTest {

    @TempDir
    Path tmp;

    private VoiceProfileFactory factory(boolean allowUserVoice) {
        return new VoiceProfileFactory(MediaTestFactory.props(tmp, allowUserVoice, 7, 0.5));
    }

    @Test
    void defaultsToNeutralVoice() {
        VoiceProfile profile = factory(false).resolve(null, null, false);
        assertThat(profile.mode()).isEqualTo(VoiceProfileMode.NEUTRAL);
    }

    @Test
    void userOwnedDeniedWhenFeatureDisabled() {
        assertThatThrownBy(() -> factory(false).resolve("user_owned", "my-voice", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void userOwnedDeniedWithoutConsentEvenWhenEnabled() {
        assertThatThrownBy(() -> factory(true).resolve("user_owned", "my-voice", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("consent");
    }

    @Test
    void userOwnedAllowedWhenEnabledAndConsented() {
        VoiceProfile profile = factory(true).resolve("user_owned", "my-voice", true);
        assertThat(profile.mode()).isEqualTo(VoiceProfileMode.USER_OWNED);
        assertThat(profile.voiceId()).isEqualTo("my-voice");
        assertThat(profile.consentConfirmed()).isTrue();
    }
}
