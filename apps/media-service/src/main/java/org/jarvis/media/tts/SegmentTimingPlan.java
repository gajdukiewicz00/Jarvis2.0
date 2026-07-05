package org.jarvis.media.tts;

/**
 * The result of duration-matching one dub segment: where it sits on the merged
 * track's timeline, whether it needs to be sped up to fit its cue window, and how
 * much trailing silence (if any) pads it out to the next segment.
 *
 * @param index               segment ordinal
 * @param timelineOffsetMs    where this segment's audio should start in the merged track
 * @param targetDurationMs    the cue window's duration ({@code cueEndMs - cueStartMs})
 * @param sourceDurationMs    the synthesized clip's actual duration
 * @param speedFactor         playback speed multiplier to apply (>= 1.0; 1.0 = no change).
 *                            Speech is only ever sped up, never slowed down.
 * @param trailingSilenceMs   silence to append after playback so the segment exactly fills
 *                            its cue window when the clip (after speed-up) finishes early
 * @param residualOverrunMs   time by which the segment still overruns its cue window after
 *                            the maximum allowed speed-up — a genuine desync risk, 0 when none
 */
public record SegmentTimingPlan(
        int index,
        long timelineOffsetMs,
        long targetDurationMs,
        long sourceDurationMs,
        double speedFactor,
        long trailingSilenceMs,
        long residualOverrunMs) {

    public boolean needsSpeedAdjustment() {
        return Math.abs(speedFactor - 1.0) > 1e-6;
    }

    public boolean hasResidualOverrun() {
        return residualOverrunMs > 0;
    }
}
