package org.jarvis.orchestrator.command.risk;

import org.jarvis.commands.DangerousAction;
import org.jarvis.commands.RiskLevel;

/**
 * Phase 5 — classification of an intent: its risk level plus, for
 * MEDIUM+ commands, which dangerous-action category it belongs to.
 */
public record RiskClassification(RiskLevel riskLevel, DangerousAction dangerousAction) {

    public static RiskClassification safe() {
        return new RiskClassification(RiskLevel.SAFE, null);
    }

    public static RiskClassification low() {
        return new RiskClassification(RiskLevel.LOW, null);
    }

    public boolean requiresConfirmation() {
        return riskLevel != null && riskLevel.ordinal() >= RiskLevel.MEDIUM.ordinal();
    }
}
