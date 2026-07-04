package org.jarvis.events;

/**
 * Phase 8 — severity tier for an event. Drives audit-projector indexing
 * and desktop-panel tinting. Maps directly onto
 * {@code org.jarvis.commands.agent.AgentEvent.Severity} but is duplicated
 * here so {@code event-schema} stays free of {@code command-schema}
 * dependencies.
 */
public enum EventSeverity {
    INFO,
    WARN,
    ERROR
}
