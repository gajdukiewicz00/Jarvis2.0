package org.jarvis.media.ffmpeg;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
                "ffmpeg", Path.of("/media/orig.mkv"), Path.of("/w/ru.srt"), Path.of("/w/ru.wav"), Path.of("/w/final.mkv"));

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
                "ffmpeg", Path.of("/o.mkv"), Path.of("/w/ru.srt"), null, Path.of("/w/final.mkv"));
        assertThat(args).containsSequence("-i", "/w/ru.srt");
        assertThat(args).doesNotContain("-metadata:s:a:1");
    }
}
