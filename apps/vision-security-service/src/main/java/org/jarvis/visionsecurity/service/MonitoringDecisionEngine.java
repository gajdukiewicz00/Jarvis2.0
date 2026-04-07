package org.jarvis.visionsecurity.service;

import org.jarvis.visionsecurity.model.DecisionType;

import java.time.Duration;
import java.time.Instant;

public class MonitoringDecisionEngine {

    private final int requiredUnknownFrames;
    private final Duration cooldown;
    private int consecutiveUnknownFrames;
    private Instant lastAlertAt;

    public MonitoringDecisionEngine(int requiredUnknownFrames, Duration cooldown) {
        this.requiredUnknownFrames = Math.max(1, requiredUnknownFrames);
        this.cooldown = cooldown == null ? Duration.ZERO : cooldown;
    }

    public synchronized Observation observe(DecisionType decision, Instant observedAt) {
        Instant now = observedAt == null ? Instant.now() : observedAt;
        boolean alertTriggered = false;

        if (decision == DecisionType.UNKNOWN_PERSON) {
            consecutiveUnknownFrames++;
            boolean cooldownElapsed = lastAlertAt == null || !lastAlertAt.plus(cooldown).isAfter(now);
            if (consecutiveUnknownFrames >= requiredUnknownFrames && cooldownElapsed) {
                alertTriggered = true;
                lastAlertAt = now;
                consecutiveUnknownFrames = 0;
            }
        } else {
            consecutiveUnknownFrames = 0;
        }

        return new Observation(alertTriggered, consecutiveUnknownFrames, lastAlertAt);
    }

    public synchronized void reset() {
        consecutiveUnknownFrames = 0;
        lastAlertAt = null;
    }

    public synchronized int consecutiveUnknownFrames() {
        return consecutiveUnknownFrames;
    }

    public record Observation(boolean alertTriggered, int unknownStreak, Instant lastAlertAt) {
    }
}
