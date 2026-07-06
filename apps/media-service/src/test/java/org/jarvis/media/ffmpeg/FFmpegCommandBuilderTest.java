package org.jarvis.media.ffmpeg;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class FFmpegCommandBuilderTest {

    private final FFmpegCommandBuilder builder = new FFmpegCommandBuilder();

    @Test
    void extractAudioBuildsSafeArgvMappingTheStream() {
        List<String> args = builder.extractAudio(
                "ffmpeg", Path.of("/media/in.mkv"), 1, Path.of("/w/out.wav"), AudioFormat.WAV);

        assertThat(args).startsWith("ffmpeg");
        assertThat(args).containsSequence("-i", "/media/in.mkv");
        assertThat(args).containsSequence("-map", "0:1");
        assertThat(args).contains("-vn", "-acodec", "pcm_s16le");
        assertThat(args).last().isEqualTo("/w/out.wav");
        // input and output are distinct: extraction never edits the source in place
        assertThat(args).doesNotContainSequence("-i", "/w/out.wav");
    }

    @Test
    void extractAudioKeepsHostileFilenameAsOneArgument() {
        String hostile = "/media/in; rm -rf / .mkv";
        List<String> args = builder.extractAudio(
                "ffmpeg", Path.of(hostile), 0, Path.of("/w/out.flac"), AudioFormat.FLAC);
        assertThat(args).contains(hostile);
        assertThat(args).noneMatch(a -> a.equals("rm"));
    }

    @Test
    void muxPreservesOriginalAndAddsRussianTracks() {
        List<String> args = builder.mux(
                "ffmpeg", Path.of("/media/orig.mkv"), Path.of("/w/ru.srt"), Path.of("/w/ru.wav"),
                1, 0, Path.of("/w/final.mkv"));

        // original is input 0 and is mapped wholesale; output is a separate file
        assertThat(args).containsSequence("-i", "/media/orig.mkv");
        assertThat(args).containsSequence("-map", "0");
        assertThat(args).containsSequence("-c", "copy");
        assertThat(args).contains("language=rus");
        assertThat(args).last().isEqualTo("/w/final.mkv");
        assertThat(args).doesNotContainSequence("-i", "/w/final.mkv");
    }

    @Test
    void muxWithSubtitleOnlyOmitsAudioMapping() {
        List<String> args = builder.mux(
                "ffmpeg", Path.of("/o.mkv"), Path.of("/w/ru.srt"), null, 1, 0, Path.of("/w/final.mkv"));
        assertThat(args).containsSequence("-i", "/w/ru.srt");
        assertThat(args).doesNotContain("-metadata:s:a:1");
    }

    @Test
    void muxTagsNewAudioAndSubtitleTracksAtTheirActualOutputIndexNotAFixedOne() {
        // Original has 2 pre-existing audio streams and 1 pre-existing subtitle stream, so
        // -map 0 (copying every original stream first) puts the appended Russian audio at
        // output index 2 and the appended Russian subtitle at output index 1 — not the
        // fixed 1/0 the bug hardcoded regardless of the original's actual stream layout.
        List<String> args = builder.mux(
                "ffmpeg", Path.of("/media/orig.mkv"), Path.of("/w/ru.srt"), Path.of("/w/ru.wav"),
                2, 1, Path.of("/w/final.mkv"));

        assertThat(args).containsSequence("-metadata:s:a:2", "language=rus");
        assertThat(args).containsSequence("-metadata:s:a:2", "title=Russian (neutral TTS)");
        assertThat(args).containsSequence("-metadata:s:s:1", "language=rus");
        assertThat(args).doesNotContain("-metadata:s:a:1");
        assertThat(args).doesNotContain("-metadata:s:s:0");
    }

    @Test
    void muxRejectsNegativeStreamCounts() {
        assertThat(catchThrowable(() -> builder.mux(
                "ffmpeg", Path.of("/o.mkv"), null, Path.of("/w/ru.wav"), -1, 0, Path.of("/w/final.mkv"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(catchThrowable(() -> builder.mux(
                "ffmpeg", Path.of("/o.mkv"), Path.of("/w/ru.srt"), null, 0, -1, Path.of("/w/final.mkv"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
