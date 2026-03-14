package org.jarvis.pccontrol.model;

import java.util.Map;

public record DesktopCapabilities(
        String displayServer,
        Map<String, Boolean> runtimeTools,
        Map<String, Boolean> operationSupport,
        Map<String, Boolean> degraded) {
}
