package org.jarvis.planner.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlanModeTest {

    @Test
    void fromTextMapsExactEnumNamesCaseInsensitively() {
        assertThat(PlanMode.fromText("deep_work")).isEqualTo(PlanMode.DEEP_WORK);
        assertThat(PlanMode.fromText("STUDY")).isEqualTo(PlanMode.STUDY);
        assertThat(PlanMode.fromText("Recovery")).isEqualTo(PlanMode.RECOVERY);
    }

    @Test
    void fromTextMapsHyphenatedAndSpacedVariants() {
        assertThat(PlanMode.fromText("deep-work")).isEqualTo(PlanMode.DEEP_WORK);
        assertThat(PlanMode.fromText("minimum viable day")).isEqualTo(PlanMode.MINIMUM_VIABLE_DAY);
    }

    @Test
    void fromTextDefaultsToNormalForNullBlankOrUnknownInput() {
        assertThat(PlanMode.fromText(null)).isEqualTo(PlanMode.NORMAL);
        assertThat(PlanMode.fromText("")).isEqualTo(PlanMode.NORMAL);
        assertThat(PlanMode.fromText("   ")).isEqualTo(PlanMode.NORMAL);
        assertThat(PlanMode.fromText("gibberish")).isEqualTo(PlanMode.NORMAL);
    }
}
