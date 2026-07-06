package org.jarvis.syncservice.service;

import org.jarvis.syncservice.domain.ConflictLogEntry.ConflictReason;
import org.jarvis.syncservice.domain.SyncRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConflictResolverTest {

    private final ConflictResolver resolver = new ConflictResolver();

    private static SyncRecord record(String recordId, Instant updatedAt) {
        return new SyncRecord("dedup-1", recordId, "dev-a", updatedAt, Map.of("v", recordId), 0L);
    }

    @Test
    void noExistingRecord_incomingWinsWithoutConflict() {
        SyncRecord incoming = record("r-1", Instant.parse("2026-06-01T00:00:00Z"));

        ConflictResolver.Resolution resolution = resolver.resolve(null, incoming);

        assertThat(resolution.winner()).isEqualTo(incoming);
        assertThat(resolution.conflict()).isNull();
        assertThat(resolution.isClash()).isFalse();
    }

    @Test
    void identicalRecordId_isTreatedAsReplayNotConflict() {
        SyncRecord existing = record("r-1", Instant.parse("2026-06-01T00:00:00Z"));
        SyncRecord incoming = record("r-1", Instant.parse("2026-06-01T00:00:00Z"));

        ConflictResolver.Resolution resolution = resolver.resolve(existing, incoming);

        assertThat(resolution.winner()).isEqualTo(existing);
        assertThat(resolution.conflict()).isNull();
    }

    @Test
    void newerUpdatedAt_incomingWinsAndConflictIsLogged() {
        SyncRecord existing = record("r-old", Instant.parse("2026-06-01T00:00:00Z"));
        SyncRecord incoming = record("r-new", Instant.parse("2026-06-02T00:00:00Z"));

        ConflictResolver.Resolution resolution = resolver.resolve(existing, incoming);

        assertThat(resolution.winner()).isEqualTo(incoming);
        assertThat(resolution.isClash()).isTrue();
        assertThat(resolution.conflict().winningRecordId()).isEqualTo("r-new");
        assertThat(resolution.conflict().reason()).isEqualTo(ConflictReason.NEWER_TIMESTAMP);
        assertThat(resolution.conflict().dedupKey()).isEqualTo("dedup-1");
        assertThat(resolution.conflict().incomingRecordId()).isEqualTo("r-new");
        assertThat(resolution.conflict().existingRecordId()).isEqualTo("r-old");
    }

    @Test
    void olderUpdatedAt_existingWinsAndConflictIsLogged() {
        SyncRecord existing = record("r-current", Instant.parse("2026-06-02T00:00:00Z"));
        SyncRecord incoming = record("r-stale", Instant.parse("2026-06-01T00:00:00Z"));

        ConflictResolver.Resolution resolution = resolver.resolve(existing, incoming);

        assertThat(resolution.winner()).isEqualTo(existing);
        assertThat(resolution.isClash()).isTrue();
        assertThat(resolution.conflict().winningRecordId()).isEqualTo("r-current");
        assertThat(resolution.conflict().reason()).isEqualTo(ConflictReason.NEWER_TIMESTAMP);
    }

    @Test
    void exactTimestampTie_lexicographicallyGreaterRecordIdWins() {
        Instant same = Instant.parse("2026-06-01T00:00:00Z");
        SyncRecord existing = record("aaa", same);
        SyncRecord incoming = record("zzz", same);

        ConflictResolver.Resolution resolution = resolver.resolve(existing, incoming);

        assertThat(resolution.winner()).isEqualTo(incoming);
        assertThat(resolution.conflict().reason()).isEqualTo(ConflictReason.TIEBREAKER_RECORD_ID);
        assertThat(resolution.conflict().winningRecordId()).isEqualTo("zzz");
    }

    @Test
    void exactTimestampTie_isDeterministicRegardlessOfArrivalOrder() {
        Instant same = Instant.parse("2026-06-01T00:00:00Z");
        SyncRecord a = record("aaa", same);
        SyncRecord z = record("zzz", same);

        // "zzz" wins whichever side of the pair it arrives as.
        assertThat(resolver.resolve(a, z).winner().recordId()).isEqualTo("zzz");
        assertThat(resolver.resolve(z, a).winner().recordId()).isEqualTo("zzz");
    }
}
