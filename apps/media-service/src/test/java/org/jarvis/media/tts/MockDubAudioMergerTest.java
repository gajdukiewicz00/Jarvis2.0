package org.jarvis.media.tts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MockDubAudioMergerTest {

    @TempDir
    Path tmp;

    private final MockDubAudioMerger merger = new MockDubAudioMerger();

    @Test
    void writesNonEmptyPlaceholderWithoutTouchingInputs() throws IOException {
        Path seg0 = tmp.resolve("seg0.wav");
        Files.writeString(seg0, "SEGMENT-AUDIO");
        Path output = tmp.resolve("dub.ru.wav");

        merger.merge(List.of(new DubSegmentAudio(seg0, new SegmentTimingPlan(0, 0, 1000, 1000, 1.0, 0, 0))), output);

        assertThat(Files.isRegularFile(output)).isTrue();
        assertThat(Files.size(output)).isGreaterThan(0);
        assertThat(Files.readString(seg0)).isEqualTo("SEGMENT-AUDIO"); // input untouched
    }
}
