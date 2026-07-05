package org.jarvis.media.tts;

import java.nio.file.Path;

/** One synthesized segment clip paired with its computed {@link SegmentTimingPlan}. */
public record DubSegmentAudio(Path wavPath, SegmentTimingPlan plan) {
}
