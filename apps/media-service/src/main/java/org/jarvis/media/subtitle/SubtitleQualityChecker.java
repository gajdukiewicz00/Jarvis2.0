package org.jarvis.media.subtitle;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Flags quality concerns in a translated subtitle track: cues that run too long,
 * low-confidence recognition, timing overlaps, and empty translations.
 */
@Component
public class SubtitleQualityChecker {

    public List<SubtitleWarning> check(List<TranslatedSegment> segments, int maxSegmentSeconds, double minConfidence) {
        List<SubtitleWarning> warnings = new ArrayList<>();
        long maxMs = (long) maxSegmentSeconds * 1000L;
        TranslatedSegment previous = null;
        for (TranslatedSegment seg : segments) {
            if (seg.isBlank()) {
                warnings.add(new SubtitleWarning(SubtitleWarning.EMPTY_TRANSLATION, seg.index(),
                        "Segment " + seg.index() + " has no translated text"));
            }
            if (seg.durationMs() > maxMs) {
                warnings.add(new SubtitleWarning(SubtitleWarning.LONG_SEGMENT, seg.index(),
                        "Segment " + seg.index() + " is " + seg.durationMs() + "ms (> " + maxMs + "ms)"));
            }
            if (seg.confidence() != null && seg.confidence() < minConfidence) {
                warnings.add(new SubtitleWarning(SubtitleWarning.LOW_CONFIDENCE, seg.index(),
                        "Segment " + seg.index() + " confidence " + seg.confidence() + " < " + minConfidence));
            }
            if (previous != null && seg.startMs() < previous.endMs()) {
                warnings.add(new SubtitleWarning(SubtitleWarning.OVERLAP, seg.index(),
                        "Segment " + seg.index() + " starts before segment " + previous.index() + " ends"));
            }
            previous = seg;
        }
        return List.copyOf(warnings);
    }
}
