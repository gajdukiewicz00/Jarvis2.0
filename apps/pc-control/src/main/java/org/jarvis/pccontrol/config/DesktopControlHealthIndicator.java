package org.jarvis.pccontrol.config;

import lombok.RequiredArgsConstructor;
import org.jarvis.pccontrol.model.DesktopCapabilities;
import org.jarvis.pccontrol.service.CapabilityDetector;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class DesktopControlHealthIndicator implements HealthIndicator {

    private final CapabilityDetector capabilityDetector;

    @Override
    public Health health() {
        try {
            DesktopCapabilities caps = capabilityDetector.detect();
            String readiness = deriveReadiness(caps);
            String limitation = deriveLimitation(caps);

            Health.Builder builder = Health.up()
                    .withDetail("displayServer", caps.displayServer())
                    .withDetail("readiness", readiness)
                    .withDetail("tools", caps.runtimeTools())
                    .withDetail("operationSupport", caps.operationSupport())
                    .withDetail("degraded", caps.degraded());

            if (limitation != null) {
                builder.withDetail("limitation", limitation);
            }

            return builder.build();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }

    static String deriveReadiness(DesktopCapabilities caps) {
        Map<String, Boolean> ops = caps.operationSupport();
        if ("headless".equals(caps.displayServer())) {
            boolean anyOp = ops.values().stream().anyMatch(Boolean::booleanValue);
            return anyOp ? "LIMITED" : "UNAVAILABLE";
        }
        boolean allOps = ops.values().stream().allMatch(Boolean::booleanValue);
        if (allOps) return "READY";
        boolean anyOps = ops.values().stream().anyMatch(Boolean::booleanValue);
        return anyOps ? "DEGRADED" : "LIMITED";
    }

    static String deriveLimitation(DesktopCapabilities caps) {
        if ("headless".equals(caps.displayServer())) {
            return "Headless environment — desktop operations unavailable";
        }

        List<String> issues = new ArrayList<>();

        if (Boolean.TRUE.equals(caps.degraded().get("xwaylandOnly"))) {
            issues.add("Wayland session — input/window control limited to XWayland clients");
        }

        if (!Boolean.TRUE.equals(caps.operationSupport().get("inputControlSupported"))
                && !Boolean.TRUE.equals(caps.degraded().get("xwaylandOnly"))) {
            issues.add("xdotool missing — input control unavailable");
        }

        if (!Boolean.TRUE.equals(caps.operationSupport().get("windowControlSupported"))) {
            issues.add("No window management tool available");
        }

        if (!Boolean.TRUE.equals(caps.operationSupport().get("audioControlSupported"))) {
            issues.add("No audio backend available");
        }

        if (!Boolean.TRUE.equals(caps.operationSupport().get("openFileSupported"))) {
            issues.add("xdg-open missing — file/URL opening unavailable");
        }

        return issues.isEmpty() ? null : String.join("; ", issues);
    }
}
