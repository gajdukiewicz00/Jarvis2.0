package org.jarvis.syncservice.repository;

import org.jarvis.syncservice.config.SyncServiceProperties;
import org.jarvis.syncservice.domain.ConflictLogEntry;
import org.jarvis.syncservice.domain.SyncRecord;
import org.jarvis.syncservice.service.ConflictResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Phase 12 — volatile in-memory {@link SyncRecordStore}.
 *
 * <p>Process-local; acceptable for the diploma demo (one operator, one desktop).
 * A JPA implementation will replace this in Phase 12-bis when sync-service runs
 * on k8s with multiple replicas. Don't add features here that wouldn't survive
 * that swap.</p>
 *
 * <p>{@link #ingest(SyncRecord)} resolves each dedup key via
 * {@link ConcurrentHashMap#compute}, which the JDK guarantees runs at most one
 * remapping function at a time per key — this is what makes concurrent writes
 * to the same dedup key resolve deterministically instead of racing.</p>
 */
@Component
@ConditionalOnMissingBean(value = SyncRecordStore.class, ignored = InMemorySyncRecordStore.class)
public class InMemorySyncRecordStore implements SyncRecordStore {

    private final ConflictResolver resolver;
    private final int conflictLogCapacity;

    private final Map<String, SyncRecord> currentByDedupKey = new ConcurrentHashMap<>();
    private final ConcurrentSkipListMap<Long, SyncRecord> bySequence = new ConcurrentSkipListMap<>();
    private final ConcurrentLinkedDeque<ConflictLogEntry> conflictLog = new ConcurrentLinkedDeque<>();
    private final AtomicLong sequence = new AtomicLong(0);

    public InMemorySyncRecordStore(ConflictResolver resolver, SyncServiceProperties props) {
        this.resolver = resolver;
        this.conflictLogCapacity = props.getRecordsConflictLogCapacity();
    }

    @Override
    public IngestOutcome ingest(SyncRecord incoming) {
        AtomicReference<IngestOutcome> outcomeRef = new AtomicReference<>();
        currentByDedupKey.compute(incoming.dedupKey(), (key, existing) -> {
            ConflictResolver.Resolution resolution = resolver.resolve(existing, incoming);
            boolean wasDuplicate = existing != null && existing.recordId().equals(incoming.recordId());
            boolean incomingWon = resolution.winner().recordId().equals(incoming.recordId())
                    && !wasDuplicate;

            SyncRecord newValue = existing;
            if (existing == null || incomingWon) {
                newValue = resolution.winner().withSequence(sequence.incrementAndGet());
                bySequence.put(newValue.sequence(), newValue);
            }

            if (resolution.conflict() != null) {
                recordConflict(resolution.conflict());
            }
            outcomeRef.set(new IngestOutcome(newValue, wasDuplicate, resolution.conflict()));
            return newValue;
        });
        return outcomeRef.get();
    }

    @Override
    public Optional<SyncRecord> findByDedupKey(String dedupKey) {
        return Optional.ofNullable(currentByDedupKey.get(dedupKey));
    }

    @Override
    public List<SyncRecord> findChangedSince(long sinceSequence, int limit) {
        List<SyncRecord> page = new ArrayList<>(Math.min(Math.max(limit, 0), 64));
        for (SyncRecord r : bySequence.tailMap(sinceSequence, false).values()) {
            if (page.size() >= limit) break;
            page.add(r);
        }
        return page;
    }

    @Override
    public long latestSequence() {
        return sequence.get();
    }

    @Override
    public int size() {
        return currentByDedupKey.size();
    }

    @Override
    public List<ConflictLogEntry> conflictLog() {
        return List.copyOf(conflictLog);
    }

    private void recordConflict(ConflictLogEntry entry) {
        conflictLog.addLast(entry);
        while (conflictLog.size() > conflictLogCapacity) {
            conflictLog.pollFirst();
        }
    }
}
