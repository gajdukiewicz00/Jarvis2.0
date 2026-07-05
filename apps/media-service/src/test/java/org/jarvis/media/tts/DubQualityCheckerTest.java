package org.jarvis.media.tts;

import org.jarvis.media.asr.TranscriptSegment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DubQualityCheckerTest {

    private final DubQualityChecker checker = new DubQualityChecker();

    private SegmentTimingPlan flatPlan(int index, long offsetMs) {
        return new SegmentTimingPlan(index, offsetMs, 1000, 1000, 1.0, 0, 0);
    }

    @Test
    void cleanRunHasNoWarningsAndNoSyncRisk() {
        List<TranscriptSegment> segs = List.of(
                new TranscriptSegment(0, 0, 1000, "hi", "S1", 0.9),
                new TranscriptSegment(1, 1000, 2000, "there", "S1", 0.9));
        List<TtsResult> results = List.of(new TtsResult(1000, 10), new TtsResult(1000, 10));
        List<SegmentTimingPlan> plans = List.of(flatPlan(0, 0), flatPlan(1, 1000));

        DubQualityReport report = checker.check(segs, results, plans, 7, 0.5);

        assertThat(report.segmentCount()).isEqualTo(2);
        assertThat(report.missingTts()).isZero();
        assertThat(report.durationMismatches()).isZero();
        assertThat(report.overlappingSpeakers()).isZero();
        assertThat(report.tooLongSegments()).isZero();
        assertThat(report.lowConfidenceSegments()).isZero();
        assertThat(report.badSyncRisk()).isFalse();
        assertThat(report.notes()).isEmpty();
    }

    @Test
    void flagsMissingTtsWhenResultHasNoBytes() {
        List<TranscriptSegment> segs = List.of(new TranscriptSegment(0, 0, 1000, "hi", "S1", 0.9));
        List<TtsResult> results = List.of(new TtsResult(0, 0));
        List<SegmentTimingPlan> plans = List.of(flatPlan(0, 0));

        DubQualityReport report = checker.check(segs, results, plans, 7, 0.5);

        assertThat(report.missingTts()).isEqualTo(1);
        assertThat(report.notes()).anyMatch(n -> n.contains("no TTS audio produced"));
    }

    @Test
    void flagsDurationMismatchWhenDriftExceedsFloor() {
        // 200ms cue, but synthesized clip is 5000ms -> drift well past the mismatch floor.
        List<TranscriptSegment> segs = List.of(new TranscriptSegment(0, 0, 200, "hi", "S1", 0.9));
        List<TtsResult> results = List.of(new TtsResult(5000, 10));
        List<SegmentTimingPlan> plans = List.of(flatPlan(0, 0));

        DubQualityReport report = checker.check(segs, results, plans, 7, 0.5);

        assertThat(report.durationMismatches()).isEqualTo(1);
    }

    @Test
    void flagsOverlappingSpeakersOnlyWhenSpeakerDiffers() {
        List<TranscriptSegment> segs = List.of(
                new TranscriptSegment(0, 0, 2000, "hi", "S1", 0.9),
                new TranscriptSegment(1, 1500, 3000, "cut in", "S2", 0.9)); // starts before seg0 ends
        List<TtsResult> results = List.of(new TtsResult(2000, 10), new TtsResult(1500, 10));
        List<SegmentTimingPlan> plans = List.of(flatPlan(0, 0), flatPlan(1, 1500));

        DubQualityReport report = checker.check(segs, results, plans, 7, 0.5);

        assertThat(report.overlappingSpeakers()).isEqualTo(1);
    }

    @Test
    void sameSpeakerOverlapIsNotFlagged() {
        List<TranscriptSegment> segs = List.of(
                new TranscriptSegment(0, 0, 2000, "hi", "S1", 0.9),
                new TranscriptSegment(1, 1500, 3000, "continued", "S1", 0.9));
        List<TtsResult> results = List.of(new TtsResult(2000, 10), new TtsResult(1500, 10));
        List<SegmentTimingPlan> plans = List.of(flatPlan(0, 0), flatPlan(1, 1500));

        DubQualityReport report = checker.check(segs, results, plans, 7, 0.5);

        assertThat(report.overlappingSpeakers()).isZero();
    }

    @Test
    void flagsTooLongSegmentsAgainstConfiguredMax() {
        List<TranscriptSegment> segs = List.of(new TranscriptSegment(0, 0, 10_000, "long cue", "S1", 0.9));
        List<TtsResult> results = List.of(new TtsResult(10_000, 10));
        List<SegmentTimingPlan> plans = List.of(flatPlan(0, 0));

        DubQualityReport report = checker.check(segs, results, plans, 7, 0.5);

        assertThat(report.tooLongSegments()).isEqualTo(1);
    }

    @Test
    void flagsLowConfidenceSegments() {
        List<TranscriptSegment> segs = List.of(new TranscriptSegment(0, 0, 1000, "hi", "S1", 0.2));
        List<TtsResult> results = List.of(new TtsResult(1000, 10));
        List<SegmentTimingPlan> plans = List.of(flatPlan(0, 0));

        DubQualityReport report = checker.check(segs, results, plans, 7, 0.5);

        assertThat(report.lowConfidenceSegments()).isEqualTo(1);
    }

    @Test
    void nullConfidenceIsNeverFlaggedAsLowConfidence() {
        List<TranscriptSegment> segs = List.of(new TranscriptSegment(0, 0, 1000, "hi", "S1", null));
        List<TtsResult> results = List.of(new TtsResult(1000, 10));
        List<SegmentTimingPlan> plans = List.of(flatPlan(0, 0));

        DubQualityReport report = checker.check(segs, results, plans, 7, 0.5);

        assertThat(report.lowConfidenceSegments()).isZero();
    }

    @Test
    void residualOverrunInPlanMarksBadSyncRiskEvenWithoutLargeAccumulatedDrift() {
        List<TranscriptSegment> segs = List.of(new TranscriptSegment(0, 0, 1000, "hi", "S1", 0.9));
        List<TtsResult> results = List.of(new TtsResult(1000, 10));
        SegmentTimingPlan withResidual = new SegmentTimingPlan(0, 0, 1000, 1000, 2.0, 0, 500);

        DubQualityReport report = checker.check(segs, results, List.of(withResidual), 7, 0.5);

        assertThat(report.badSyncRisk()).isTrue();
        assertThat(report.notes()).anyMatch(n -> n.contains("after maximum speed-up"));
    }

    @Test
    void largeAccumulatedDriftMarksBadSyncRisk() {
        List<TranscriptSegment> segs = List.of(
                new TranscriptSegment(0, 0, 1000, "a", "S1", 0.9),
                new TranscriptSegment(1, 1000, 2000, "b", "S1", 0.9));
        // Both segments overrun by 2000ms each -> 4000ms total drift, past the 3000ms threshold.
        List<TtsResult> results = List.of(new TtsResult(3000, 10), new TtsResult(3000, 10));
        List<SegmentTimingPlan> plans = List.of(flatPlan(0, 0), flatPlan(1, 1000));

        DubQualityReport report = checker.check(segs, results, plans, 7, 0.5);

        assertThat(report.badSyncRisk()).isTrue();
    }
}
