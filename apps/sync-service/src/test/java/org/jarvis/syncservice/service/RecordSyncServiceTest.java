package org.jarvis.syncservice.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.jarvis.syncservice.config.SyncServiceProperties;
import org.jarvis.syncservice.domain.SyncRecord;
import org.jarvis.syncservice.repository.InMemorySyncRecordStore;
import org.jarvis.syncservice.service.RecordSyncService.DeltaPage;
import org.jarvis.syncservice.service.RecordSyncService.InvalidSyncRecordException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecordSyncServiceTest {

    private RecordSyncService service;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        SyncServiceProperties props = new SyncServiceProperties();
        props.setRecordsDeltaDefaultPageSize(2);
        props.setRecordsDeltaMaxPageSize(3);
        InMemorySyncRecordStore store = new InMemorySyncRecordStore(new ConflictResolver(), props);
        meterRegistry = new SimpleMeterRegistry();
        service = new RecordSyncService(store, new SyncMetrics(meterRegistry), props);
    }

    private static SyncRecord record(String dedupKey, String recordId, Instant updatedAt) {
        return new SyncRecord(dedupKey, recordId, "dev-a", updatedAt, Map.of("v", recordId), 0L);
    }

    @Test
    void ingest_newRecord_recordsStoredMetric() {
        service.ingest(record("k1", "r1", Instant.parse("2026-06-01T00:00:00Z")));

        assertThat(meterRegistry.get("sync.records.ingest").tag("status", "stored").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void ingest_replay_recordsDuplicateMetric() {
        SyncRecord rec = record("k1", "r1", Instant.parse("2026-06-01T00:00:00Z"));
        service.ingest(rec);
        service.ingest(rec);

        assertThat(meterRegistry.get("sync.records.ingest").tag("status", "duplicate").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void ingest_conflict_recordsConflictResolvedMetric() {
        service.ingest(record("k1", "r-old", Instant.parse("2026-06-01T00:00:00Z")));
        service.ingest(record("k1", "r-new", Instant.parse("2026-06-02T00:00:00Z")));

        assertThat(meterRegistry.get("sync.records.ingest").tag("status", "conflict_resolved").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void ingest_blankDedupKey_throwsInvalidSyncRecordException() {
        SyncRecord bad = record("", "r1", Instant.now());

        assertThatThrownBy(() -> service.ingest(bad))
                .isInstanceOf(InvalidSyncRecordException.class)
                .hasMessageContaining("dedupKey");
    }

    @Test
    void ingest_blankRecordId_throwsInvalidSyncRecordException() {
        SyncRecord bad = record("k1", "  ", Instant.now());

        assertThatThrownBy(() -> service.ingest(bad))
                .isInstanceOf(InvalidSyncRecordException.class)
                .hasMessageContaining("recordId");
    }

    @Test
    void delta_noLimit_usesConfiguredDefaultPageSize() {
        for (int i = 0; i < 5; i++) {
            service.ingest(record("k" + i, "r" + i, Instant.parse("2026-06-01T00:00:00Z").plusSeconds(i)));
        }

        DeltaPage page = service.delta(0, null);

        assertThat(page.records()).hasSize(2); // defaultPageSize configured to 2
        assertThat(page.hasMore()).isTrue();
        assertThat(page.sinceCursor()).isEqualTo(0);
        assertThat(page.nextCursor()).isEqualTo(page.records().get(1).sequence());
    }

    @Test
    void delta_requestedLimitAboveMax_isClampedToConfiguredMax() {
        for (int i = 0; i < 5; i++) {
            service.ingest(record("k" + i, "r" + i, Instant.parse("2026-06-01T00:00:00Z").plusSeconds(i)));
        }

        DeltaPage page = service.delta(0, 1000);

        assertThat(page.records()).hasSize(3); // maxPageSize configured to 3
        assertThat(page.hasMore()).isTrue();
    }

    @Test
    void delta_pagingThroughCursorEventuallyReachesEnd() {
        for (int i = 0; i < 4; i++) {
            service.ingest(record("k" + i, "r" + i, Instant.parse("2026-06-01T00:00:00Z").plusSeconds(i)));
        }

        DeltaPage first = service.delta(0, 3);
        assertThat(first.records()).hasSize(3);
        assertThat(first.hasMore()).isTrue();

        DeltaPage second = service.delta(first.nextCursor(), 3);
        assertThat(second.records()).hasSize(1);
        assertThat(second.hasMore()).isFalse();
        assertThat(second.nextCursor()).isEqualTo(second.records().get(0).sequence());
    }

    @Test
    void delta_pastEnd_returnsEmptyPageAndEchoesCursor() {
        service.ingest(record("k1", "r1", Instant.parse("2026-06-01T00:00:00Z")));

        DeltaPage page = service.delta(999, 10);

        assertThat(page.records()).isEmpty();
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isEqualTo(999);
    }

    @Test
    void conflictLog_reflectsResolvedClashes() {
        service.ingest(record("k1", "r-old", Instant.parse("2026-06-01T00:00:00Z")));
        service.ingest(record("k1", "r-new", Instant.parse("2026-06-02T00:00:00Z")));

        assertThat(service.conflictLog()).hasSize(1);
        assertThat(service.conflictLog().get(0).winningRecordId()).isEqualTo("r-new");
    }
}
