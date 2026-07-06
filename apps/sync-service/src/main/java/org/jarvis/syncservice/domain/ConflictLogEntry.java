package org.jarvis.syncservice.domain;

import java.time.Instant;

/**
 * Phase 12 — audit trail entry written whenever two different writes race for the
 * same {@code dedupKey}. Kept in-process (see {@code RecordSyncService}) so an
 * operator can inspect exactly which side won and why via the diagnostic
 * {@code GET /api/v1/sync/records/conflicts} endpoint, without needing the Kafka
 * audit pipeline for this transient, debugging-only concern.
 */
public record ConflictLogEntry(
        String dedupKey,
        String incomingRecordId,
        Instant incomingUpdatedAt,
        String existingRecordId,
        Instant existingUpdatedAt,
        String winningRecordId,
        ConflictReason reason,
        Instant resolvedAt) {

    /** Why the winner won: strictly newer {@code updatedAt}, or a tie broken by recordId. */
    public enum ConflictReason { NEWER_TIMESTAMP, TIEBREAKER_RECORD_ID }
}
