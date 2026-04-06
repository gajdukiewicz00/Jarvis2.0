package org.jarvis.pccontrol.securitymonitoring.model;

import java.time.Duration;

public record MonitoringStatusSnapshot(
        boolean enabled,
        Duration samplingInterval,
        MonitoringRuntimeState runtimeState,
        MonitoringCheckReport lastReport) {
}
