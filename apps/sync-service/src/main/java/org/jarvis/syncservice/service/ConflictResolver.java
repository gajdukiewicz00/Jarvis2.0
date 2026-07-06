package org.jarvis.syncservice.service;

import org.jarvis.syncservice.domain.ConflictLogEntry;
import org.jarvis.syncservice.domain.ConflictLogEntry.ConflictReason;
import org.jarvis.syncservice.domain.SyncRecord;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Phase 12 — deterministic last-write-wins conflict resolution for two
 * {@link SyncRecord}s that share a dedup key.
 *
 * <p>"Deterministic" means: given the same pair of candidate records, every
 * replica (and every retry) reaches the same winner without needing a shared
 * clock or a coordinator. {@code updatedAt} decides first; if both candidates
 * carry the exact same instant, the lexicographically greater {@code recordId}
 * breaks the tie so the outcome is still reproducible.</p>
 */
@Component
public class ConflictResolver {

    /**
     * @param winner   exactly one of the two inputs passed to {@link #resolve}
     * @param conflict {@code null} when there was nothing to resolve (no prior
     *                 record, or the incoming record is an idempotent replay of
     *                 the record already stored); non-null whenever two
     *                 different writes actually clashed.
     */
    public record Resolution(SyncRecord winner, ConflictLogEntry conflict) {
        public boolean isClash() { return conflict != null; }
    }

    /**
     * @param existing the record currently stored for this dedup key, or {@code null} if none
     * @param incoming the record just received for the same dedup key
     */
    public Resolution resolve(SyncRecord existing, SyncRecord incoming) {
        if (existing == null) {
            return new Resolution(incoming, null);
        }
        if (existing.recordId().equals(incoming.recordId())) {
            // Exact replay of an already-applied write: not a conflict, the caller dedups.
            return new Resolution(existing, null);
        }

        int cmp = incoming.updatedAt().compareTo(existing.updatedAt());
        boolean incomingWins;
        ConflictReason reason;
        if (cmp != 0) {
            incomingWins = cmp > 0;
            reason = ConflictReason.NEWER_TIMESTAMP;
        } else {
            incomingWins = incoming.recordId().compareTo(existing.recordId()) > 0;
            reason = ConflictReason.TIEBREAKER_RECORD_ID;
        }

        SyncRecord winner = incomingWins ? incoming : existing;
        ConflictLogEntry conflict = new ConflictLogEntry(
                existing.dedupKey(),
                incoming.recordId(), incoming.updatedAt(),
                existing.recordId(), existing.updatedAt(),
                winner.recordId(), reason, Instant.now());
        return new Resolution(winner, conflict);
    }
}
