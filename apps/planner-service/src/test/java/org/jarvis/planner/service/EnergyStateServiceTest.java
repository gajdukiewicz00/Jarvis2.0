package org.jarvis.planner.service;

import org.jarvis.planner.model.EnergyLevel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EnergyStateServiceTest {

    @Test
    void defaultsToNormalThenSetAndGet() {
        EnergyStateService service = new EnergyStateService();

        assertThat(service.get("u1")).isEqualTo(EnergyLevel.NORMAL);

        service.set("u1", EnergyLevel.EXHAUSTED);
        assertThat(service.get("u1")).isEqualTo(EnergyLevel.EXHAUSTED);

        // null normalizes to NORMAL; isolation between users
        service.set("u1", null);
        assertThat(service.get("u1")).isEqualTo(EnergyLevel.NORMAL);
        assertThat(service.get("u2")).isEqualTo(EnergyLevel.NORMAL);
    }
}
