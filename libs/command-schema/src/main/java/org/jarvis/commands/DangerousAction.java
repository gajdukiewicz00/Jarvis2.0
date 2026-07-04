package org.jarvis.commands;

/**
 * Phase 5 — canonical list of action categories that the SPEC requires
 * confirmation for. Each {@link CommandEnvelope#getIntent()} maps to at
 * most one {@code DangerousAction} (or none, for SAFE/LOW operations).
 *
 * <p>Order in this enum has no semantic meaning; the gating decision uses
 * {@link RiskLevel}.</p>
 */
public enum DangerousAction {
    /** Removing files from disk or trash. */
    DELETE_FILES,
    /** Sending messages on behalf of the user (chat, email, SMS, etc.). */
    SEND_MESSAGES,
    /** Running an arbitrary shell command on the host. */
    RUN_SHELL,
    /** Anything that moves money: transfer, purchase, subscription change. */
    SPEND_MONEY,
    /** Physical world: door locks, garage, gates. */
    OPEN_DOORS,
    /** Shutdown / reboot / suspend / sleep / log out. */
    SHUTDOWN,
    /** Auth/security knobs: password, token rotation, MFA, kill-switch. */
    CHANGE_SECURITY,
    /** Mass deletion or rewrite of memory entries (Obsidian / Postgres). */
    BULK_MEMORY_MODIFY
}
