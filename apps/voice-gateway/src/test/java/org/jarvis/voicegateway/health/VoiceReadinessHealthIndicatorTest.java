package org.jarvis.voicegateway.health;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoiceReadinessHealthIndicatorTest {

    @Mock
    private VoiceReadinessService voiceReadinessService;

    @Test
    void healthReflectsCurrentSnapshotStatusAndDetails() {
        VoiceReadinessService.DownstreamRouteSnapshot route = new VoiceReadinessService.DownstreamRouteSnapshot(
                "UP", "API_GATEWAY_ROUTE_READY", "reachable", Map.of());
        VoiceReadinessService.Snapshot snapshot = new VoiceReadinessService.Snapshot(
                "UP",
                Map.of("stt", "UP"),
                Map.of(),
                route);
        when(voiceReadinessService.currentSnapshot()).thenReturn(snapshot);

        VoiceReadinessHealthIndicator indicator = new VoiceReadinessHealthIndicator(voiceReadinessService);
        Health health = indicator.health();

        assertEquals(new Status("UP"), health.getStatus());
        assertEquals(Map.of("stt", "UP"), health.getDetails().get("components"));
        assertEquals(route, health.getDetails().get("apiGatewayRoute"));
    }

    @Test
    void healthReflectsDownStatus() {
        VoiceReadinessService.DownstreamRouteSnapshot route = new VoiceReadinessService.DownstreamRouteSnapshot(
                "DOWN", "API_GATEWAY_UNREACHABLE", "unreachable", Map.of());
        VoiceReadinessService.Snapshot snapshot = new VoiceReadinessService.Snapshot(
                "DOWN",
                Map.of("stt", "DOWN"),
                Map.of(),
                route);
        when(voiceReadinessService.currentSnapshot()).thenReturn(snapshot);

        VoiceReadinessHealthIndicator indicator = new VoiceReadinessHealthIndicator(voiceReadinessService);
        Health health = indicator.health();

        assertEquals(new Status("DOWN"), health.getStatus());
    }
}
