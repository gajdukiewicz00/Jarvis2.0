package org.jarvis.vision.config;

import lombok.RequiredArgsConstructor;
import org.jarvis.common.vision.VisionHealthResponse;
import org.jarvis.vision.service.VisionStatusService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class VisionHealthIndicator implements HealthIndicator {

    private final VisionStatusService visionStatusService;

    @Override
    public Health health() {
        VisionHealthResponse response = visionStatusService.health();
        Health.Builder builder = response.available() ? Health.up() : Health.down();
        return builder
                .withDetail("detectorProvider", response.detectorProvider())
                .withDetail("verifierProvider", response.verifierProvider())
                .withDetail("message", response.message())
                .withDetails(response.diagnostics())
                .build();
    }
}
