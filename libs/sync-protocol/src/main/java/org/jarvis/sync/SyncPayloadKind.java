package org.jarvis.sync;

/**
 * Phase 12 — what the plaintext inside a {@link SyncEnvelope} represents.
 *
 * <p>Add new kinds at the end. Old consumers default unknown kinds to
 * {@link #UNKNOWN} so a newer Android client can ship a kind the local
 * sync-service hasn't shipped support for yet without crashing.</p>
 */
public enum SyncPayloadKind {
    FINANCE_ENTRY,
    COMMAND_INTENT,
    DEVICE_HEARTBEAT,
    HEALTH_ENTRY,
    UNKNOWN
}
