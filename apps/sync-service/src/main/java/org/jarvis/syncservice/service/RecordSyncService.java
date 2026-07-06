package org.jarvis.syncservice.service;

import org.jarvis.syncservice.config.SyncServiceProperties;
import org.jarvis.syncservice.domain.ConflictLogEntry;
import org.jarvis.syncservice.domain.SyncRecord;
import org.jarvis.syncservice.repository.SyncRecordStore;
import org.jarvis.syncservice.repository.SyncRecordStore.IngestOutcome;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Phase 12 — use-case layer over {@link SyncRecordStore}: ingests one record at a
 * time (dedup + LWW conflict resolution happen in the store; this records the
 * outcome as a metric), and serves the delta-sync change feed with a bounded,
 * config-driven page size.
 */
@Service
public class RecordSyncService {

    private final SyncRecordStore store;
    private final SyncMetrics metrics;
    private final int defaultPageSize;
    private final int maxPageSize;

    public RecordSyncService(SyncRecordStore store, SyncMetrics metrics, SyncServiceProperties props) {
        this.store = store;
        this.metrics = metrics;
        this.defaultPageSize = props.getRecordsDeltaDefaultPageSize();
        this.maxPageSize = props.getRecordsDeltaMaxPageSize();
    }

    /** Thrown when the inbound request is missing a required field (blank dedupKey/recordId). */
    public static final class InvalidSyncRecordException extends RuntimeException {
        public InvalidSyncRecordException(String reason) { super(reason); }
    }

    public IngestOutcome ingest(SyncRecord incoming) {
        if (incoming.dedupKey().isBlank()) {
            throw new InvalidSyncRecordException("dedupKey must not be blank");
        }
        if (incoming.recordId().isBlank()) {
            throw new InvalidSyncRecordException("recordId must not be blank");
        }
        IngestOutcome outcome = store.ingest(incoming);
        metrics.recordRecordIngest(statusOf(outcome));
        return outcome;
    }

    /**
     * @param records    up to {@code limit} records changed since {@code sinceCursor}, oldest first
     * @param sinceCursor the cursor the caller asked for (echoed back for convenience)
     * @param nextCursor  the cursor to pass on the next call; equals {@code sinceCursor}
     *                    when nothing changed
     * @param hasMore     {@code true} when more changed records exist beyond this page
     */
    public record DeltaPage(List<SyncRecord> records, long sinceCursor, long nextCursor, boolean hasMore) {}

    public DeltaPage delta(long sinceCursor, Integer requestedLimit) {
        int limit = clamp(requestedLimit);
        // Fetch one extra row so we can tell "exactly filled the page" apart from "more remain"
        // without a second round-trip to the store.
        List<SyncRecord> page = store.findChangedSince(sinceCursor, limit + 1);
        boolean hasMore = page.size() > limit;
        List<SyncRecord> bounded = hasMore ? page.subList(0, limit) : page;
        long nextCursor = bounded.isEmpty() ? sinceCursor : bounded.get(bounded.size() - 1).sequence();
        return new DeltaPage(List.copyOf(bounded), sinceCursor, nextCursor, hasMore);
    }

    public List<ConflictLogEntry> conflictLog() {
        return store.conflictLog();
    }

    private int clamp(Integer requested) {
        if (requested == null || requested <= 0) {
            return defaultPageSize;
        }
        return Math.min(requested, maxPageSize);
    }

    private static String statusOf(IngestOutcome outcome) {
        if (outcome.wasDuplicate()) return "duplicate";
        if (outcome.hadConflict()) return "conflict_resolved";
        return "stored";
    }
}
