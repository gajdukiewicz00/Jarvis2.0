package org.jarvis.voicegateway.health;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("voiceReadiness")
@RequiredArgsConstructor
public class VoiceReadinessHealthIndicator implements HealthIndicator {

    private final VoiceReadinessService voiceReadinessService;

    @Override
    public Health health() {
        VoiceReadinessService.Snapshot snapshot = voiceReadinessService.currentSnapshot();
        return Health.status(snapshot.status())
                .withDetail("components", snapshot.components())
                .withDetail("componentDetails", snapshot.componentDetails())
                .withDetail("apiGatewayRoute", snapshot.apiGatewayRoute())
                .build();
    }
}
