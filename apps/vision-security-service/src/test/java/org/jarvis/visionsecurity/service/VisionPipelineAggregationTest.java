package org.jarvis.visionsecurity.service;

import org.jarvis.visionsecurity.model.DecisionType;
import org.jarvis.visionsecurity.model.FaceMatch;
import org.jarvis.visionsecurity.model.FaceVerdict;
import org.jarvis.visionsecurity.model.RectBox;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VisionPipelineAggregationTest {

    private static final RectBox BOX_A = new RectBox(0, 0, 100, 100);
    private static final RectBox BOX_B = new RectBox(120, 0, 100, 100);
    private static final RectBox BOX_C = new RectBox(240, 0, 100, 100);

    @Test
    void noFaceDetectedAlwaysProducesNoFaceRegardlessOfEnrollment() {
        DecisionType decision = VisionPipelineService.aggregateDecision(true, List.of(), true, true);
        assertThat(decision).isEqualTo(DecisionType.NO_FACE);
        assertThat(VisionPipelineService.reasonFor(true, List.of(), true, true))
                .isEqualTo("No face detected in the current frame");
    }

    @Test
    void unenrolledUserKeepsDecisionUncertain() {
        List<FaceMatch> matches = List.of(new FaceMatch(BOX_A, FaceVerdict.OWNER, 30.0));
        DecisionType decision = VisionPipelineService.aggregateDecision(false, matches, false, true);
        assertThat(decision).isEqualTo(DecisionType.UNCERTAIN);
        assertThat(VisionPipelineService.reasonFor(false, matches, false, true))
                .contains("Owner enrollment is missing");
    }

    @Test
    void singleOwnerFaceProducesOwnerPresent() {
        List<FaceMatch> matches = List.of(new FaceMatch(BOX_A, FaceVerdict.OWNER, 30.0));
        DecisionType decision = VisionPipelineService.aggregateDecision(true, matches, false, true);
        assertThat(decision).isEqualTo(DecisionType.OWNER_PRESENT);
    }

    @Test
    void strangerNextToOwnerEscalatesToUnknownPersonWhenStrictMultiFaceEnabled() {
        List<FaceMatch> matches = List.of(
                new FaceMatch(BOX_A, FaceVerdict.OWNER, 30.0),
                new FaceMatch(BOX_B, FaceVerdict.UNKNOWN, 120.0)
        );

        DecisionType decision = VisionPipelineService.aggregateDecision(true, matches, false, true);
        String reason = VisionPipelineService.reasonFor(true, matches, false, true);

        assertThat(decision).isEqualTo(DecisionType.UNKNOWN_PERSON);
        assertThat(reason).contains("Owner detected together with an unknown person");
    }

    @Test
    void strangerNextToOwnerStaysSilencedWhenStrictMultiFaceDisabled() {
        List<FaceMatch> matches = List.of(
                new FaceMatch(BOX_A, FaceVerdict.OWNER, 30.0),
                new FaceMatch(BOX_B, FaceVerdict.UNKNOWN, 120.0)
        );

        DecisionType decision = VisionPipelineService.aggregateDecision(true, matches, false, false);

        assertThat(decision).isEqualTo(DecisionType.OWNER_PRESENT);
    }

    @Test
    void uncertainAlongsideOwnerKeepsOwnerVerdict() {
        List<FaceMatch> matches = List.of(
                new FaceMatch(BOX_A, FaceVerdict.OWNER, 30.0),
                new FaceMatch(BOX_B, FaceVerdict.UNCERTAIN, 90.0)
        );

        DecisionType decision = VisionPipelineService.aggregateDecision(true, matches, false, true);

        assertThat(decision).isEqualTo(DecisionType.OWNER_PRESENT);
    }

    @Test
    void allUnknownFacesProduceUnknownPerson() {
        List<FaceMatch> matches = List.of(
                new FaceMatch(BOX_A, FaceVerdict.UNKNOWN, 130.0),
                new FaceMatch(BOX_B, FaceVerdict.UNKNOWN, 140.0)
        );

        DecisionType decision = VisionPipelineService.aggregateDecision(true, matches, false, true);

        assertThat(decision).isEqualTo(DecisionType.UNKNOWN_PERSON);
    }

    @Test
    void onlyUncertainFacesProduceUncertain() {
        List<FaceMatch> matches = List.of(new FaceMatch(BOX_A, FaceVerdict.UNCERTAIN, 90.0));
        DecisionType decision = VisionPipelineService.aggregateDecision(true, matches, false, true);
        assertThat(decision).isEqualTo(DecisionType.UNCERTAIN);
    }

    @Test
    void threeWayMixWithStrangerAndOwnerAndUncertainStillEscalates() {
        List<FaceMatch> matches = List.of(
                new FaceMatch(BOX_A, FaceVerdict.OWNER, 30.0),
                new FaceMatch(BOX_B, FaceVerdict.UNCERTAIN, 90.0),
                new FaceMatch(BOX_C, FaceVerdict.UNKNOWN, 130.0)
        );
        DecisionType decision = VisionPipelineService.aggregateDecision(true, matches, false, true);
        assertThat(decision).isEqualTo(DecisionType.UNKNOWN_PERSON);
    }
}
