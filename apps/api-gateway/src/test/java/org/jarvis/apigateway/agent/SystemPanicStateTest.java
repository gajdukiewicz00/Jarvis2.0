package org.jarvis.apigateway.agent;

import org.jarvis.common.safety.SystemPanicState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SystemPanicStateTest {

    @Test
    void engageThenClearTogglesState() {
        SystemPanicState panic = new SystemPanicState();
        assertThat(panic.isEngaged()).isFalse();

        panic.engage("tester", "drill", 1000L);
        assertThat(panic.isEngaged()).isTrue();
        assertThat(panic.snapshot()).containsEntry("engaged", true);
        assertThat(panic.snapshot()).containsEntry("reason", "drill");

        panic.clear("tester", 2000L);
        assertThat(panic.isEngaged()).isFalse();
        assertThat(panic.snapshot()).containsEntry("engaged", false);
    }
}
