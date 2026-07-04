package org.jarvis.commands;

/**
 * Phase 4 / Phase 5 — risk classification for a command.
 *
 * <p>The orchestrator labels every command before publishing. Phase 5
 * confirmation flow uses this enum to decide whether the command may run
 * directly (SAFE / LOW) or must wait for owner confirmation (MEDIUM and
 * above by default; CRITICAL always asks even with relaxed policy).</p>
 */
public enum RiskLevel {
    /** Read-only or self-contained: queries, status, idempotent reads. */
    SAFE,
    /** Side-effects but reversible: open an app, focus a window. */
    LOW,
    /** User-visible state change: send a notification, modify a setting. */
    MEDIUM,
    /** Privileged or hard-to-reverse: write a file, run a shell command. */
    HIGH,
    /** Destructive or external: delete data, spend money, open a door, shutdown. */
    CRITICAL
}
