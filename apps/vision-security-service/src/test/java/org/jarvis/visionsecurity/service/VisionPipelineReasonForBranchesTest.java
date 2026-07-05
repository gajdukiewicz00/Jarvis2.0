package org.jarvis.visionsecurity.service;

import org.jarvis.visionsecurity.model.FaceMatch;
import org.jarvis.visionsecurity.model.FaceVerdict;
import org.jarvis.visionsecurity.model.RectBox;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the {@code reasonFor} branches not exercised by
 * {@link VisionPipelineAggregationTest} (which focuses on {@code aggregateDecision}
 * and only spot-checks a couple of {@code reasonFor} messages): the
 * "uncertain confidence" and "outside owner threshold" fallback reasons.
 */
class VisionPipelineReasonForBranchesTest {

    private static final RectBox BOX_A = new RectBox(0, 0, 100, 100);
    private static final RectBox BOX_B = new RectBox(120, 0, 100, 100);

    @Test
    void reasonMentionsUncertainConfidenceWhenNoOwnerOrStrangerButUncertainPresent() {
        List<FaceMatch> matches = List.of(new FaceMatch(BOX_A, FaceVerdict.UNCERTAIN, 90.0));

        String reason = VisionPipelineService.reasonFor(true, matches, false, true);

        assertThat(reason).isEqualTo(
                "Faces were detected but verification confidence stayed between owner and unknown thresholds");
    }

    @Test
    void reasonFallsBackToOutsideThresholdWhenOnlyUnknownFacesPresent() {
        List<FaceMatch> matches = List.of(
                new FaceMatch(BOX_A, FaceVerdict.UNKNOWN, 130.0),
                new FaceMatch(BOX_B, FaceVerdict.UNKNOWN, 140.0)
        );

        String reason = VisionPipelineService.reasonFor(true, matches, false, true);

        assertThat(reason).isEqualTo("Detected faces stayed outside the owner threshold");
    }
}
