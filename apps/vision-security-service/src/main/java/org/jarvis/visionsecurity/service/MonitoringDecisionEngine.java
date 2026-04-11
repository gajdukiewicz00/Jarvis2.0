package org.jarvis.visionsecurity.service;

import org.jarvis.visionsecurity.model.DecisionType;

import java.time.Duration;
import java.time.Instant;

public class MonitoringDecisionEngine {

    private final int requiredUnknownFrames;
    private final Duration cooldown;
    private final int ownerGraceFrames;
    private int consecutiveUnknownFrames;
    private int neutralFramesSinceOwner;
    private Instant lastAlertAt;
    private DecisionType effectiveDecision = DecisionType.NO_FACE;

    public MonitoringDecisionEngine(int requiredUnknownFrames, Duration cooldown) {
        this(requiredUnknownFrames, cooldown, 0);
    }

    public MonitoringDecisionEngine(int requiredUnknownFrames, Duration cooldown, int ownerGraceFrames) {
        this.requiredUnknownFrames = Math.max(1, requiredUnknownFrames);
        this.cooldown = cooldown == null ? Duration.ZERO : cooldown;
        this.ownerGraceFrames = Math.max(0, ownerGraceFrames);
    }

    public synchronized Observation observe(DecisionType decision, Instant observedAt) {
        Instant now = observedAt == null ? Instant.now() : observedAt;
        boolean alertTriggered = false;
        DecisionType frameDecision = decision == null ? DecisionType.NO_FACE : decision;
        boolean ownerWasStable = effectiveDecision == DecisionType.OWNER_PRESENT;

        if (frameDecision == DecisionType.OWNER_PRESENT) {
            consecutiveUnknownFrames = 0;
            neutralFramesSinceOwner = 0;
            effectiveDecision = DecisionType.OWNER_PRESENT;
        } else if (frameDecision == DecisionType.UNKNOWN_PERSON) {
            consecutiveUnknownFrames++;
            neutralFramesSinceOwner = 0;
            if (ownerWasStable && consecutiveUnknownFrames <= ownerGraceFrames) {
                effectiveDecision = DecisionType.OWNER_PRESENT;
            } else if (consecutiveUnknownFrames < requiredUnknownFrames) {
                effectiveDecision = DecisionType.UNCERTAIN;
            } else {
                effectiveDecision = DecisionType.UNKNOWN_PERSON;
                boolean cooldownElapsed = lastAlertAt == null || !lastAlertAt.plus(cooldown).isAfter(now);
                if (cooldownElapsed) {
                    alertTriggered = true;
                    lastAlertAt = now;
                    consecutiveUnknownFrames = 0;
                }
            }
        } else {
            consecutiveUnknownFrames = 0;
            if (ownerWasStable && neutralFramesSinceOwner < ownerGraceFrames) {
                neutralFramesSinceOwner++;
                effectiveDecision = DecisionType.OWNER_PRESENT;
            } else {
                neutralFramesSinceOwner = 0;
                effectiveDecision = frameDecision;
            }
        }

        return new Observation(alertTriggered, consecutiveUnknownFrames, lastAlertAt, effectiveDecision);
    }

    public synchronized void reset() {
        consecutiveUnknownFrames = 0;
        neutralFramesSinceOwner = 0;
        lastAlertAt = null;
        effectiveDecision = DecisionType.NO_FACE;
    }

    public synchronized int consecutiveUnknownFrames() {
        return consecutiveUnknownFrames;
    }

    public record Observation(boolean alertTriggered, int unknownStreak, Instant lastAlertAt, DecisionType effectiveDecision) {
    }
}
