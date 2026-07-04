package org.jarvis.media.asr;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TranscriptCodecTest {

    @TempDir
    Path tmp;

    private final TranscriptCodec codec = new TranscriptCodec();

    @Test
    void roundTripsTranscriptThroughJson() {
        Transcript original = new Transcript("en", List.of(
                new TranscriptSegment(0, 0, 1000, "Hello", "S1", 0.9),
                new TranscriptSegment(1, 1000, 2000, "World", null, null)));
        Path file = tmp.resolve("transcript.json");

        codec.write(file, original);
        Transcript read = codec.read(file);

        assertThat(read.language()).isEqualTo("en");
        assertThat(read.segments()).hasSize(2);
        assertThat(read.segments().get(0).text()).isEqualTo("Hello");
        assertThat(read.segments().get(0).speakerId()).isEqualTo("S1");
        assertThat(read.segments().get(1).confidence()).isNull();
    }
}
