package org.jarvis.analytics.config;

import feign.FeignException;
import feign.Request;
import feign.RetryableException;
import org.jarvis.analytics.client.LifeTrackerClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
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

    @Test
    void healthIsDownWithTimeoutDetailWhenLifeTrackerCallTimesOut() {
        LifeTrackerClient client = mock(LifeTrackerClient.class);
        RetryableException timeout = new RetryableException(
                -1,
                "Read timed out executing GET http://life-tracker:8085/actuator/health/readiness",
                Request.HttpMethod.GET,
                new ConnectException("timeout"),
                (Long) null,
                feignRequest());
        when(client.getReadiness()).thenThrow(timeout);

        Health health = new LifeTrackerHealthIndicator(client).health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("timeout", health.getDetails().get("status"));
        assertEquals("Connection timeout", health.getDetails().get("error"));
    }

    @Test
    void healthIsDownWithHttpStatusWhenLifeTrackerReturnsFeignError() {
        LifeTrackerClient client = mock(LifeTrackerClient.class);
        when(client.getReadiness()).thenThrow(new StubFeignException(503, "service unavailable"));

        Health health = new LifeTrackerHealthIndicator(client).health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("error", health.getDetails().get("status"));
        assertEquals(503, health.getDetails().get("httpStatus"));
    }

    @Test
    void healthIsDownWithUnknownStatusWhenLifeTrackerThrowsUnexpectedRuntimeException() {
        LifeTrackerClient client = mock(LifeTrackerClient.class);
        when(client.getReadiness()).thenThrow(new IllegalStateException("boom"));

        Health health = new LifeTrackerHealthIndicator(client).health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("unknown", health.getDetails().get("status"));
        assertEquals("boom", health.getDetails().get("error"));
    }

    private Request feignRequest() {
        return Request.create(Request.HttpMethod.GET, "http://life-tracker:8085/actuator/health/readiness",
                Map.of(), null, StandardCharsets.UTF_8, null);
    }

    private static final class StubFeignException extends FeignException {
        private StubFeignException(int status, String message) {
            super(status, message, Request.create(Request.HttpMethod.GET,
                    "http://life-tracker:8085/actuator/health/readiness", Map.of(), null, StandardCharsets.UTF_8, null),
                    new byte[0], Map.of());
        }
    }
}
