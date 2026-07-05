package org.jarvis.media.tts;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/** Pure duration-matching math: speed-up-vs-pad decisions per dub segment. */
class SegmentTimingPlannerTest {

    private final SegmentTimingPlanner planner = new SegmentTimingPlanner();

    @Test
    void perfectFitNeedsNoAdjustment() {
        SegmentTimingPlan plan = planOne(new SegmentTimingInput(0, 1000, 3000, 2000));

        assertThat(plan.timelineOffsetMs()).isEqualTo(1000);
        assertThat(plan.needsSpeedAdjustment()).isFalse();
        assertThat(plan.trailingSilenceMs()).isZero();
        assertThat(plan.hasResidualOverrun()).isFalse();
    }

    @Test
    void underrunClipIsPaddedWithTrailingSilenceNeverSlowedDown() {
        // cue is 3000ms, clip finishes in 1500ms.
        SegmentTimingPlan plan = planOne(new SegmentTimingInput(1, 0, 3000, 1500));

        assertThat(plan.speedFactor()).isEqualTo(1.0);
        assertThat(plan.trailingSilenceMs()).isEqualTo(1500);
        assertThat(plan.hasResidualOverrun()).isFalse();
    }

    @Test
    void moderateOverrunIsSpedUpWithinFfmpegAtempoRange() {
        // cue 1000ms, clip runs 1600ms -> raw factor 1.6, within [0.5, 2.0], no clamp needed.
        SegmentTimingPlan plan = planOne(new SegmentTimingInput(2, 500, 1500, 1600));

        assertThat(plan.needsSpeedAdjustment()).isTrue();
        assertThat(plan.speedFactor()).isCloseTo(1.6, offset(1e-6));
        assertThat(plan.hasResidualOverrun()).isFalse();
        assertThat(plan.trailingSilenceMs()).isZero();
    }

    @Test
    void extremeOverrunClampsSpeedAndReportsResidualOverrun() {
        // cue 1000ms, clip runs 5000ms -> raw factor 5.0, clamped to 2.0 -> adjusted 2500ms -> 1500ms residual.
        SegmentTimingPlan plan = planOne(new SegmentTimingInput(3, 0, 1000, 5000));

        assertThat(plan.speedFactor()).isEqualTo(2.0);
        assertThat(plan.hasResidualOverrun()).isTrue();
        assertThat(plan.residualOverrunMs()).isEqualTo(1500);
        assertThat(plan.trailingSilenceMs()).isZero();
    }

    @Test
    void zeroSynthesizedDurationIsTreatedAsNoAdjustmentNeeded() {
        SegmentTimingPlan plan = planOne(new SegmentTimingInput(4, 0, 2000, 0));

        assertThat(plan.speedFactor()).isEqualTo(1.0);
        assertThat(plan.trailingSilenceMs()).isZero();
        assertThat(plan.hasResidualOverrun()).isFalse();
    }

    @Test
    void zeroOrNegativeCueWindowIsSafelyHandled() {
        SegmentTimingPlan plan = planOne(new SegmentTimingInput(5, 1000, 1000, 800));

        assertThat(plan.targetDurationMs()).isZero();
        assertThat(plan.speedFactor()).isEqualTo(1.0);
        assertThat(plan.hasResidualOverrun()).isFalse();
    }

    @Test
    void planPreservesOrderAndIndexAcrossMultipleSegments() {
        List<SegmentTimingPlan> plans = planner.plan(List.of(
                new SegmentTimingInput(0, 0, 1000, 900),
                new SegmentTimingInput(1, 1000, 2000, 2500)));

        assertThat(plans).hasSize(2);
        assertThat(plans.get(0).index()).isEqualTo(0);
        assertThat(plans.get(1).index()).isEqualTo(1);
        assertThat(plans.get(1).timelineOffsetMs()).isEqualTo(1000);
    }

    private SegmentTimingPlan planOne(SegmentTimingInput input) {
        return planner.plan(List.of(input)).get(0);
    }
}
