package org.jarvis.media.tts;

import org.jarvis.media.asr.TranscriptSegment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Flags dubbing quality concerns across a Russian dub run: segments with no
 * synthesized audio, segments whose synthesized duration drifts materially from the
 * cue window, adjacent segments from different speakers whose cues overlap, cues
 * that are themselves too long to dub naturally, source segments with low ASR
 * confidence, and an overall desync risk (accumulated drift, or any segment that
 * still overruns its cue after the maximum allowed speed-up).
 */
@Component
public class DubQualityChecker {

    private static final long MISMATCH_FLOOR_MS = 1500;
    private static final long SYNC_RISK_DRIFT_MS = 3000;

    public DubQualityReport check(List<TranscriptSegment> segments, List<TtsResult> results,
                                  List<SegmentTimingPlan> plans, int maxSegmentSeconds, double minConfidence) {
        List<String> notes = new ArrayList<>();
        long maxMs = (long) maxSegmentSeconds * 1000L;
        int missing = 0;
        int mismatches = 0;
        int overlaps = 0;
        int tooLong = 0;
        int lowConfidence = 0;
        long totalDrift = 0;
        boolean anyResidualOverrun = false;
        TranscriptSegment previous = null;

        for (int i = 0; i < segments.size(); i++) {
            TranscriptSegment seg = segments.get(i);
            TtsResult result = results.get(i);

            if (result.isMissing()) {
                missing++;
                notes.add("Segment " + seg.index() + ": no TTS audio produced");
            }

            long cueMs = seg.durationMs();
            long drift = result.synthesizedDurationMs() - cueMs;
            totalDrift += drift;
            if (Math.abs(drift) > Math.max(MISMATCH_FLOOR_MS, cueMs / 2)) {
                mismatches++;
                notes.add("Segment " + seg.index() + ": dubbed " + result.synthesizedDurationMs()
                        + "ms vs cue " + cueMs + "ms");
            }

            if (cueMs > maxMs) {
                tooLong++;
                notes.add("Segment " + seg.index() + ": cue is " + cueMs + "ms (> " + maxMs + "ms)");
            }

            if (seg.confidence() != null && seg.confidence() < minConfidence) {
                lowConfidence++;
                notes.add("Segment " + seg.index() + ": ASR confidence " + seg.confidence()
                        + " < " + minConfidence);
            }

            if (i < plans.size() && plans.get(i).hasResidualOverrun()) {
                anyResidualOverrun = true;
                notes.add("Segment " + seg.index() + ": still overruns by " + plans.get(i).residualOverrunMs()
                        + "ms after maximum speed-up");
            }

            if (previous != null && seg.startMs() < previous.endMs() && !sameSpeaker(previous.speakerId(), seg.speakerId())) {
                overlaps++;
                notes.add("Segment " + seg.index() + ": speaker overlap with " + previous.index());
            }
            previous = seg;
        }

        boolean badSyncRisk = Math.abs(totalDrift) > SYNC_RISK_DRIFT_MS || anyResidualOverrun;
        return new DubQualityReport(segments.size(), missing, mismatches, overlaps, tooLong, lowConfidence,
                badSyncRisk, notes);
    }

    private boolean sameSpeaker(String a, String b) {
        return a != null && a.equals(b);
    }
}
