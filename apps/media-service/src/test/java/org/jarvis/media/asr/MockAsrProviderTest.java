package org.jarvis.media.asr;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockAsrProviderTest {

    private final MockAsrProvider provider = new MockAsrProvider();

    @Test
    void returnsTimedSegmentsForNormalAudio() {
        Transcript t = provider.transcribe(Path.of("/w/audio.wav"), "en");
        assertThat(t.segments()).hasSize(3);
        assertThat(t.segments().get(0).startMs()).isZero();
        assertThat(t.segments().get(0).endMs()).isGreaterThan(0);
        assertThat(t.segments().get(0).confidence()).isNotNull();
    }

    @Test
    void returnsEmptyTranscriptForSilence() {
        Transcript t = provider.transcribe(Path.of("/w/silence.wav"), null);
        assertThat(t.isEmpty()).isTrue();
        assertThat(t.language()).isEqualTo("en");
    }

    @Test
    void throwsOnCorruptAudio() {
        assertThatThrownBy(() -> provider.transcribe(Path.of("/w/corrupt-fail.wav"), "en"))
                .isInstanceOf(AsrException.class);
    }
}
