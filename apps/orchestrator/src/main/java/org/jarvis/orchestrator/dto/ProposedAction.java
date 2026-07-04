package org.jarvis.orchestrator.dto;

/** A desktop action the assistant proposes (or executed). */
public record ProposedAction(String type, String target, String classification, String reason) {
    public ProposedAction withReason(String r) { return new ProposedAction(type, target, classification, r); }
}
