package org.jarvis.syncservice.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Phase 12 — a single logical unit of synced state, keyed by a caller-supplied
 * {@code dedupKey} (e.g. {@code "finance:2026-07-01:txn-882"}).
 *
 * <ul>
 *   <li>{@code recordId} identifies <em>this particular write</em>. A retried or
 *       replayed submission carries the same {@code recordId} as the write it is
 *       retrying, so {@link org.jarvis.syncservice.repository.SyncRecordStore} can
 *       recognise it as an idempotent duplicate rather than a competing edit.</li>
 *   <li>{@code updatedAt} is the client's logical last-write timestamp, used by
 *       {@link org.jarvis.syncservice.service.ConflictResolver} to pick a winner
 *       when two different writes race for the same {@code dedupKey}.</li>
 *   <li>{@code sequence} is the server-assigned, strictly increasing cursor position
 *       used for delta sync. It is intentionally independent of {@code updatedAt} so
 *       clock skew or exact-timestamp ties across devices never make the change feed
 *       ambiguous. It is {@code 0} until the record has been persisted.</li>
 * </ul>
 */
public record SyncRecord(
        String dedupKey,
        String recordId,
        String deviceId,
        Instant updatedAt,
        Map<String, Object> payload,
        long sequence) {

    public SyncRecord {
        Objects.requireNonNull(dedupKey, "dedupKey");
        Objects.requireNonNull(recordId, "recordId");
        Objects.requireNonNull(updatedAt, "updatedAt");
        payload = payload != null ? Map.copyOf(payload) : Map.of();
    }

    /** Returns a copy of this record assigned to {@code newSequence}; used only by the
     * store at persist time so the rest of the codebase never mutates a SyncRecord. */
    public SyncRecord withSequence(long newSequence) {
        return new SyncRecord(dedupKey, recordId, deviceId, updatedAt, payload, newSequence);
    }
}
