package org.jarvis.media.tts;

/**
 * Input to {@link SegmentTimingPlanner}: one segment's cue window (from the Russian
 * transcript) paired with how long its synthesized TTS clip actually turned out to be.
 *
 * @param index                 segment ordinal (mirrors {@code TranscriptSegment.index()})
 * @param cueStartMs            cue start time on the original timeline
 * @param cueEndMs              cue end time on the original timeline
 * @param synthesizedDurationMs actual duration of the synthesized audio clip, or 0 if none was produced
 */
public record SegmentTimingInput(int index, long cueStartMs, long cueEndMs, long synthesizedDurationMs) {
}
