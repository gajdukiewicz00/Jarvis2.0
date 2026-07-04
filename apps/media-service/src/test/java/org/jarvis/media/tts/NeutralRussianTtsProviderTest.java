package org.jarvis.media.tts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NeutralRussianTtsProviderTest {

    @TempDir
    Path tmp;

    private final NeutralRussianTtsProvider provider = new NeutralRussianTtsProvider();

    @Test
    void synthesizesSegmentAudioWithEstimatedDuration() {
        Path out = tmp.resolve("seg.wav");
        TtsResult result = provider.synthesize("Привет, мир", VoiceProfile.neutral(), out);
        assertThat(result.isMissing()).isFalse();
        assertThat(result.synthesizedDurationMs()).isGreaterThan(0);
        assertThat(out.toFile().exists()).isTrue();
    }

    @Test
    void blankTextProducesNoAudio() {
        Path out = tmp.resolve("blank.wav");
        TtsResult result = provider.synthesize("   ", VoiceProfile.neutral(), out);
        assertThat(result.isMissing()).isTrue();
    }

    @Test
    void failureMarkerThrows() {
        Path out = tmp.resolve("fail.wav");
        assertThatThrownBy(() -> provider.synthesize("this will tts-fail now", VoiceProfile.neutral(), out))
                .isInstanceOf(TtsException.class);
    }

    @Test
    void neverClonesEvenForUserOwnedProfile() {
        // USER_OWNED still synthesizes the neutral placeholder — no real-person cloning
        Path out = tmp.resolve("user.wav");
        VoiceProfile userOwned = new VoiceProfile(VoiceProfileMode.USER_OWNED, "my-voice", true);
        TtsResult result = provider.synthesize("Текст", userOwned, out);
        assertThat(result.isMissing()).isFalse();
    }
}
