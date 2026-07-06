package org.jarvis.syncservice.repository;

import org.jarvis.syncservice.config.SyncServiceProperties;
import org.jarvis.syncservice.domain.SyncRecord;
import org.jarvis.syncservice.repository.SyncRecordStore.IngestOutcome;
import org.jarvis.syncservice.service.ConflictResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class InMemorySyncRecordStoreTest {

    private InMemorySyncRecordStore store;

    @BeforeEach
    void setUp() {
        SyncServiceProperties props = new SyncServiceProperties();
        props.setRecordsConflictLogCapacity(3);
        store = new InMemorySyncRecordStore(new ConflictResolver(), props);
    }

    private static SyncRecord record(String dedupKey, String recordId, Instant updatedAt) {
        return new SyncRecord(dedupKey, recordId, "dev-a", updatedAt, Map.of("k", recordId), 0L);
    }

    @Test
    void firstIngestForKey_isStoredAndAssignedSequenceOne() {
        IngestOutcome outcome = store.ingest(record("k1", "r1", Instant.parse("2026-06-01T00:00:00Z")));

        assertThat(outcome.wasDuplicate()).isFalse();
        assertThat(outcome.hadConflict()).isFalse();
        assertThat(outcome.stored().sequence()).isEqualTo(1L);
        assertThat(store.size()).isEqualTo(1);
        assertThat(store.latestSequence()).isEqualTo(1L);
    }

    @Test
    void dedupReplay_sameRecordIdTwice_doesNotDuplicateOrBumpSequence() {
        SyncRecord first = record("k1", "r1", Instant.parse("2026-06-01T00:00:00Z"));
        IngestOutcome firstOutcome = store.ingest(first);
        IngestOutcome replay = store.ingest(record("k1", "r1", Instant.parse("2026-06-01T00:00:00Z")));

        assertThat(replay.wasDuplicate()).isTrue();
        assertThat(replay.hadConflict()).isFalse();
        assertThat(replay.stored().sequence()).isEqualTo(firstOutcome.stored().sequence());
        assertThat(store.size()).isEqualTo(1);
        assertThat(store.latestSequence()).isEqualTo(1L);
    }

    @Test
    void repeatedReplay_manyTimes_neverDuplicatesInDeltaFeed() {
        store.ingest(record("k1", "r1", Instant.parse("2026-06-01T00:00:00Z")));
        for (int i = 0; i < 5; i++) {
            store.ingest(record("k1", "r1", Instant.parse("2026-06-01T00:00:00Z")));
        }

        List<SyncRecord> changed = store.findChangedSince(0, 100);
        assertThat(changed).hasSize(1);
        assertThat(store.latestSequence()).isEqualTo(1L);
    }

    @Test
    void newerConcurrentEdit_winsAndReplacesCurrentValue() {
        store.ingest(record("k1", "r-old", Instant.parse("2026-06-01T00:00:00Z")));
        IngestOutcome result = store.ingest(record("k1", "r-new", Instant.parse("2026-06-02T00:00:00Z")));

        assertThat(result.wasDuplicate()).isFalse();
        assertThat(result.hadConflict()).isTrue();
        assertThat(result.stored().recordId()).isEqualTo("r-new");
        assertThat(result.conflict().winningRecordId()).isEqualTo("r-new");
        assertThat(store.size()).isEqualTo(1);
        // A real state change bumps the sequence so the delta feed sees it.
        assertThat(result.stored().sequence()).isEqualTo(2L);
    }

    @Test
    void staleConcurrentEdit_losesAndCurrentValueIsUnchanged() {
        IngestOutcome winner = store.ingest(record("k1", "r-current", Instant.parse("2026-06-02T00:00:00Z")));
        IngestOutcome result = store.ingest(record("k1", "r-stale", Instant.parse("2026-06-01T00:00:00Z")));

        assertThat(result.wasDuplicate()).isFalse();
        assertThat(result.hadConflict()).isTrue();
        assertThat(result.stored().recordId()).isEqualTo("r-current");
        assertThat(result.conflict().winningRecordId()).isEqualTo("r-current");
        // No actual state change occurred; sequence is not bumped for the loser.
        assertThat(result.stored().sequence()).isEqualTo(winner.stored().sequence());
        assertThat(store.latestSequence()).isEqualTo(winner.stored().sequence());
    }

    @Test
    void conflictLog_capturesEveryClashAndIsBoundedByCapacity() {
        store.ingest(record("k1", "r1", Instant.parse("2026-06-01T00:00:00Z")));
        store.ingest(record("k1", "r2", Instant.parse("2026-06-02T00:00:00Z")));
        store.ingest(record("k1", "r3", Instant.parse("2026-06-03T00:00:00Z")));
        store.ingest(record("k1", "r4", Instant.parse("2026-06-04T00:00:00Z")));

        // capacity was set to 3 in setUp(); the oldest conflict entry is evicted.
        assertThat(store.conflictLog()).hasSize(3);
        assertThat(store.conflictLog().get(store.conflictLog().size() - 1).winningRecordId()).isEqualTo("r4");
    }

    @Test
    void findChangedSince_returnsOnlyRecordsAfterCursorInSequenceOrder() {
        store.ingest(record("k1", "r1", Instant.parse("2026-06-01T00:00:00Z")));
        store.ingest(record("k2", "r2", Instant.parse("2026-06-02T00:00:00Z")));
        store.ingest(record("k3", "r3", Instant.parse("2026-06-03T00:00:00Z")));

        List<SyncRecord> sinceZero = store.findChangedSince(0, 100);
        assertThat(sinceZero).extracting(SyncRecord::recordId).containsExactly("r1", "r2", "r3");

        List<SyncRecord> sinceOne = store.findChangedSince(1, 100);
        assertThat(sinceOne).extracting(SyncRecord::recordId).containsExactly("r2", "r3");

        List<SyncRecord> sinceThree = store.findChangedSince(3, 100);
        assertThat(sinceThree).isEmpty();
    }

    @Test
    void findChangedSince_respectsBoundedPageSize() {
        for (int i = 0; i < 10; i++) {
            store.ingest(record("k" + i, "r" + i, Instant.parse("2026-06-01T00:00:00Z").plusSeconds(i)));
        }

        List<SyncRecord> page = store.findChangedSince(0, 4);
        assertThat(page).hasSize(4);
        assertThat(page).extracting(SyncRecord::recordId).containsExactly("r0", "r1", "r2", "r3");
    }

    @Test
    void concurrentEditsToSameKey_resolveWithoutLosingTheDeterministicWinner() throws InterruptedException {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        Instant base = Instant.parse("2026-06-01T00:00:00Z");

        try {
            for (int i = 0; i < threads; i++) {
                int idx = i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    // All candidates share the same updatedAt so the tiebreaker (recordId) decides;
                    // "r-7" is lexicographically greatest and must win regardless of arrival order.
                    store.ingest(record("shared-key", "r-" + idx, base));
                });
            }
            ready.await();
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

            // Exactly one current value survives per dedup key, and it must be the deterministic
            // winner regardless of arrival order. Every intermediate win along the way (e.g. "r-3"
            // beating "r-1" before "r-7" itself arrives) legitimately earns its own delta-feed entry,
            // so the change log's *last* entry — not its only entry — is the assertion that holds.
            assertThat(store.size()).isEqualTo(1);
            assertThat(store.findByDedupKey("shared-key")).map(SyncRecord::recordId).contains("r-7");
            List<SyncRecord> changeLog = store.findChangedSince(0, 100);
            assertThat(changeLog).isNotEmpty();
            assertThat(changeLog.get(changeLog.size() - 1).recordId()).isEqualTo("r-7");
        } finally {
            pool.shutdownNow();
        }
    }
}
