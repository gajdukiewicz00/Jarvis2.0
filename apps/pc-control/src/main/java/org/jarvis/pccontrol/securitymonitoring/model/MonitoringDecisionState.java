package org.jarvis.pccontrol.securitymonitoring.model;

public enum MonitoringDecisionState {
    OWNER_CONFIRMED,
    OBSERVING,
    SUSPICIOUS,
    HIGH_RISK,
    ALERT_TRIGGERED,
    NO_FACE,
    UNAVAILABLE,
    DEGRADED
}
