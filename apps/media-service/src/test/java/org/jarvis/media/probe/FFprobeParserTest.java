package org.jarvis.media.probe;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FFprobeParserTest {

    private final FFprobeParser parser = new FFprobeParser();

    @Test
    void parsesMultiTrackFixtureWithCommentaryDetection() {
        List<MediaStream> streams = parser.parse(MockFFprobeClient.DEFAULT_FIXTURE);

        assertThat(streams).hasSize(4);
        List<MediaStream> audio = streams.stream().filter(MediaStream::isAudio).toList();
        assertThat(audio).hasSize(2);
        assertThat(audio.get(0).language()).isEqualTo("eng");
        assertThat(audio.get(0).channels()).isEqualTo(6);
        assertThat(audio.get(0).isCommentary()).isFalse();
        assertThat(audio.get(1).isCommentary()).isTrue(); // "Director Commentary" + disposition.comment
        assertThat(streams.stream().anyMatch(MediaStream::isSubtitle)).isTrue();
        assertThat(streams.stream().anyMatch(MediaStream::isVideo)).isTrue();
    }

    @Test
    void parsesFileWithNoAudioStreams() {
        String json = """
                {"streams":[
                  {"index":0,"codec_type":"video","codec_name":"h264","disposition":{"default":1}}
                ],"format":{"duration":"60.0"}}
                """;
        List<MediaStream> streams = parser.parse(json);
        assertThat(streams).hasSize(1);
        assertThat(streams.stream().anyMatch(MediaStream::isAudio)).isFalse();
    }

    @Test
    void rejectsInvalidJson() {
        assertThatThrownBy(() -> parser.parse("not-json{"))
                .isInstanceOf(ProbeException.class)
                .hasMessageContaining("not valid JSON");
    }

    @Test
    void rejectsEmptyOutput() {
        assertThatThrownBy(() -> parser.parse("  "))
                .isInstanceOf(ProbeException.class)
                .hasMessageContaining("no output");
    }

    @Test
    void rejectsJsonWithoutStreamsArray() {
        assertThatThrownBy(() -> parser.parse("{\"format\":{}}"))
                .isInstanceOf(ProbeException.class)
                .hasMessageContaining("no streams array");
    }
}
