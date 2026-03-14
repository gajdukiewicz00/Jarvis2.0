package org.jarvis.analytics.config;

import org.jarvis.analytics.client.LifeTrackerClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LifeTrackerHealthIndicatorTest {

    @Test
    void healthUsesReadinessEndpointInsteadOfBusinessApi() {
        LifeTrackerClient client = mock(LifeTrackerClient.class);
        when(client.getReadiness()).thenReturn(Map.of("status", "UP"));

        Health health = new LifeTrackerHealthIndicator(client).health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("/actuator/health/readiness", health.getDetails().get("endpoint"));
        verify(client).getReadiness();
        verify(client, never()).getExpenses();
    }

    @Test
    void healthIsDownWhenReadinessReportsNonUpStatus() {
        LifeTrackerClient client = mock(LifeTrackerClient.class);
        when(client.getReadiness()).thenReturn(Map.of("status", "OUT_OF_SERVICE"));

        Health health = new LifeTrackerHealthIndicator(client).health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("OUT_OF_SERVICE", health.getDetails().get("status"));
        verify(client).getReadiness();
        verify(client, never()).getExpenses();
    }
}
