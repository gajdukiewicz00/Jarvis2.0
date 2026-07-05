package org.jarvis.media.tts;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure duration-matching logic: decides, for each dub segment, how its synthesized
 * TTS clip should be fit onto the segment's cue window when the per-segment clips are
 * later merged onto one continuous track (see {@code DubAudioMergeCommandBuilder}).
 *
 * <p>Policy: speech is only ever sped up, never slowed down — an artificially slowed
 * voice reads as unnatural. When the synthesized clip overruns its cue, playback speed
 * is raised just enough to fit, clamped to {@link #MAX_SPEED_FACTOR} (ffmpeg's
 * single-stage {@code atempo} ceiling); any residual overrun past the clamp is exposed
 * via {@link SegmentTimingPlan#residualOverrunMs()} so the quality report can flag a
 * genuine desync risk. When the clip finishes early, the remaining cue time becomes
 * trailing silence instead of stretching the voice.</p>
 */
@Component
public class SegmentTimingPlanner {

    /** ffmpeg's {@code atempo} filter accepts a single stage only in [0.5, 2.0]. */
    static final double MAX_SPEED_FACTOR = 2.0;

    public List<SegmentTimingPlan> plan(List<SegmentTimingInput> inputs) {
        List<SegmentTimingPlan> plans = new ArrayList<>(inputs.size());
        for (SegmentTimingInput input : inputs) {
            plans.add(planOne(input));
        }
        return List.copyOf(plans);
    }

    private SegmentTimingPlan planOne(SegmentTimingInput input) {
        long targetMs = Math.max(0, input.cueEndMs() - input.cueStartMs());
        long sourceMs = Math.max(0, input.synthesizedDurationMs());

        if (sourceMs <= 0 || targetMs <= 0) {
            return new SegmentTimingPlan(input.index(), input.cueStartMs(), targetMs, sourceMs, 1.0, 0, 0);
        }
        if (sourceMs <= targetMs) {
            long pad = targetMs - sourceMs;
            return new SegmentTimingPlan(input.index(), input.cueStartMs(), targetMs, sourceMs, 1.0, pad, 0);
        }

        double rawFactor = (double) sourceMs / (double) targetMs;
        double factor = Math.min(rawFactor, MAX_SPEED_FACTOR);
        long adjustedMs = Math.round(sourceMs / factor);
        long residual = Math.max(0, adjustedMs - targetMs);
        long pad = residual > 0 ? 0 : Math.max(0, targetMs - adjustedMs);
        return new SegmentTimingPlan(input.index(), input.cueStartMs(), targetMs, sourceMs, factor, pad, residual);
    }
}
