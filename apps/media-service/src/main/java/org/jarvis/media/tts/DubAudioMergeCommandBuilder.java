package org.jarvis.media.tts;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds the ffmpeg argument list that merges per-segment dub audio clips onto one
 * continuous track: each clip is delayed to its planned {@link SegmentTimingPlan#timelineOffsetMs()}
 * via {@code adelay} and sped up per {@link SegmentTimingPlan#speedFactor()} via {@code atempo},
 * then every delayed stream is combined with {@code amix}. Silence naturally fills every gap —
 * no segment overlaps another because each occupies only its own delayed position on the
 * timeline. Every path is a separate argument; there is no shell and no string concatenation
 * of file names, matching the "no shell injection" posture of {@code FFmpegCommandBuilder}.
 */
@Component
public class DubAudioMergeCommandBuilder {

    public List<String> merge(String binary, List<DubSegmentAudio> segments, Path output) {
        if (segments == null || segments.isEmpty()) {
            throw new IllegalArgumentException("At least one segment is required to merge a dub track");
        }
        List<String> args = new ArrayList<>();
        args.add(binary);
        args.add("-hide_banner");
        args.add("-nostdin");
        args.add("-y");
        for (DubSegmentAudio seg : segments) {
            args.add("-i");
            args.add(seg.wavPath().toString());
        }
        args.add("-filter_complex");
        args.add(buildFilterGraph(segments));
        args.add("-map");
        args.add("[aout]");
        args.add(output.toString());
        return List.copyOf(args);
    }

    private String buildFilterGraph(List<DubSegmentAudio> segments) {
        StringBuilder graph = new StringBuilder();
        List<String> labels = new ArrayList<>(segments.size());
        for (int i = 0; i < segments.size(); i++) {
            SegmentTimingPlan plan = segments.get(i).plan();
            String label = "a" + i;
            graph.append('[').append(i).append(']');
            double factor = clampToFfmpegAtempoRange(plan.speedFactor());
            if (Math.abs(factor - 1.0) > 1e-6) {
                graph.append("atempo=").append(fmt(factor)).append(',');
            }
            long delay = Math.max(0, plan.timelineOffsetMs());
            graph.append("adelay=").append(delay).append('|').append(delay);
            graph.append('[').append(label).append(']').append(';');
            labels.add("[" + label + "]");
        }
        labels.forEach(graph::append);
        graph.append("amix=inputs=").append(segments.size())
                .append(":duration=longest:dropout_transition=0[aout]");
        return graph.toString();
    }

    /**
     * Defensive clamp — {@link SegmentTimingPlanner} already keeps speed factors within
     * ffmpeg's single-stage {@code atempo} range [0.5, 2.0] and never below 1.0, but the
     * filter graph must never be handed an out-of-range value regardless of the caller.
     */
    static double clampToFfmpegAtempoRange(double factor) {
        if (factor <= 0) {
            return 1.0;
        }
        return Math.min(2.0, Math.max(0.5, factor));
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }
}
