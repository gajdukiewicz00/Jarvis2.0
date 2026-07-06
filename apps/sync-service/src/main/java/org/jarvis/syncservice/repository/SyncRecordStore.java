package org.jarvis.syncservice.repository;

import org.jarvis.syncservice.domain.ConflictLogEntry;
import org.jarvis.syncservice.domain.SyncRecord;

import java.util.List;
import java.util.Optional;

/**
 * Phase 12 — persistence boundary for synced records: LWW conflict resolution,
 * replay dedup, and the delta-sync change feed.
 *
 * <p>Pass 1 ships an in-memory implementation suitable for the diploma demo,
 * mirroring {@link PairingStore}. The interface is here so a JPA-backed
 * implementation can drop in later without touching call sites.</p>
 */
public interface SyncRecordStore {

    /**
     * Resolves {@code incoming} against whatever is currently stored for its
     * dedup key, atomically per dedup key so two concurrent writes to the same
     * key can never both "win". Assigns the next sequence number only when the
     * stored value actually changes as a result.
     */
    IngestOutcome ingest(SyncRecord incoming);

    /** The record currently stored for this dedup key, if any (i.e. the winner of
     * every clash resolved so far for that key). */
    Optional<SyncRecord> findByDedupKey(String dedupKey);

    /** Records with sequence strictly greater than {@code sinceSequence}, oldest
     * first, capped at {@code limit} entries. */
    List<SyncRecord> findChangedSince(long sinceSequence, int limit);

    /** Highest sequence number issued so far; {@code 0} for an empty store. */
    long latestSequence();

    /** Number of distinct dedup keys currently stored. */
    int size();

    /** Bounded, most-recent-last log of every clash this store has resolved. */
    List<ConflictLogEntry> conflictLog();

    /**
     * @param stored      the current value for the incoming record's dedup key after
     *                     this call resolved (may be {@code incoming}, the previously
     *                     stored record if incoming lost, or the previously stored
     *                     record again if this was a duplicate)
     * @param wasDuplicate {@code true} when {@code incoming} was an idempotent replay
     *                     of the write already stored (same dedup key + same recordId)
     * @param conflict     non-null when a genuine clash (different recordIds for the
     *                     same dedup key) was resolved by this call
     */
    record IngestOutcome(SyncRecord stored, boolean wasDuplicate, ConflictLogEntry conflict) {
        public boolean hadConflict() { return conflict != null; }
    }
}
