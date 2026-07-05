package org.jarvis.media.tts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DubAudioMergeCommandBuilderTest {

    @TempDir
    Path tmp;

    private final DubAudioMergeCommandBuilder builder = new DubAudioMergeCommandBuilder();

    @Test
    void rejectsEmptySegmentList() {
        assertThatThrownBy(() -> builder.merge("ffmpeg", List.of(), tmp.resolve("out.wav")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyInputPathBecomesItsOwnDashIArgumentNeverConcatenated() {
        Path seg0 = tmp.resolve("seg-0000.wav");
        Path seg1 = tmp.resolve("seg-0001.wav");
        Path output = tmp.resolve("dub.ru.wav");

        List<DubSegmentAudio> segments = List.of(
                new DubSegmentAudio(seg0, new SegmentTimingPlan(0, 0, 1000, 900, 1.0, 100, 0)),
                new DubSegmentAudio(seg1, new SegmentTimingPlan(1, 1000, 1000, 1000, 1.0, 0, 0)));

        List<String> command = builder.merge("ffmpeg", segments, output);

        assertThat(command.get(0)).isEqualTo("ffmpeg");
        assertThat(command).contains("-i", seg0.toString(), seg1.toString());
        assertThat(command).endsWith(output.toString());
        assertThat(command).contains("-map", "[aout]");
    }

    @Test
    void filterGraphDelaysEachSegmentToItsTimelineOffsetAndMixesAll() {
        Path seg0 = tmp.resolve("s0.wav");
        Path seg1 = tmp.resolve("s1.wav");
        List<DubSegmentAudio> segments = List.of(
                new DubSegmentAudio(seg0, new SegmentTimingPlan(0, 500, 1000, 900, 1.0, 100, 0)),
                new DubSegmentAudio(seg1, new SegmentTimingPlan(1, 2600, 1000, 1000, 1.0, 0, 0)));

        List<String> command = builder.merge("ffmpeg", segments, tmp.resolve("out.wav"));
        String graph = command.get(command.indexOf("-filter_complex") + 1);

        assertThat(graph).contains("adelay=500|500");
        assertThat(graph).contains("adelay=2600|2600");
        assertThat(graph).contains("amix=inputs=2");
        assertThat(graph).doesNotContain("atempo="); // both plans have speedFactor=1.0
    }

    @Test
    void filterGraphAppliesAtempoWhenSpeedFactorIsNotOne() {
        Path seg0 = tmp.resolve("s0.wav");
        List<DubSegmentAudio> segments = List.of(
                new DubSegmentAudio(seg0, new SegmentTimingPlan(0, 0, 1000, 1600, 1.6, 0, 0)));

        List<String> command = builder.merge("ffmpeg", segments, tmp.resolve("out.wav"));
        String graph = command.get(command.indexOf("-filter_complex") + 1);

        assertThat(graph).contains("atempo=1.6000");
        assertThat(graph).contains("adelay=0|0");
    }

    @Test
    void clampKeepsFactorWithinFfmpegSingleStageAtempoRange() {
        assertThat(DubAudioMergeCommandBuilder.clampToFfmpegAtempoRange(3.0)).isEqualTo(2.0);
        assertThat(DubAudioMergeCommandBuilder.clampToFfmpegAtempoRange(0.1)).isEqualTo(0.5);
        assertThat(DubAudioMergeCommandBuilder.clampToFfmpegAtempoRange(1.25)).isEqualTo(1.25);
        assertThat(DubAudioMergeCommandBuilder.clampToFfmpegAtempoRange(-1)).isEqualTo(1.0);
    }
}
