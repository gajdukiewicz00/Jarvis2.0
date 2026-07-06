package org.jarvis.memory.obsidian;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Roadmap P1 #9 — TTL/expiry cleanup for {@code memory_notes}.
 *
 * <p>Notes written with an {@code expiresAt} (explicit or via
 * {@code ttlSeconds} — see {@link MemoryNoteRequest}) are periodically swept:
 * once expired, they are forgotten through {@link MemoryForgetService} so the
 * same tombstone + audit trail applies as an owner-initiated "forget this".</p>
 */
@Slf4j
@Service
public class MemoryExpiryCleanupService {

    private final MemoryNoteRepository repository;
    private final MemoryForgetService forgetService;
    private final MemoryExpiryProperties properties;
    private final Clock clock;
    private final MemoryMetrics metrics;

    public MemoryExpiryCleanupService(MemoryNoteRepository repository,
                                      MemoryForgetService forgetService,
                                      MemoryExpiryProperties properties,
                                      Clock clock,
                                      MemoryMetrics metrics) {
        this.repository = repository;
        this.forgetService = forgetService;
        this.properties = properties;
        this.clock = clock;
        this.metrics = metrics;
    }

    /** Scheduled sweep — delay configured via {@code jarvis.memory.expiry.cleanup-interval-ms}. */
    @Scheduled(fixedDelayString = "${jarvis.memory.expiry.cleanup-interval-ms:3600000}")
    public void scheduledCleanup() {
        if (!properties.isEnabled()) {
            return;
        }
        int removed = cleanupExpiredNotes();
        if (removed > 0) {
            log.info("memory TTL sweep: forgot {} expired note(s)", removed);
        }
    }

    /**
     * Forgets every ACTIVE, non-pinned note whose {@code expiresAt} is in the
     * past. Public (not just scheduler-invoked) so it is directly unit-testable
     * and callable on-demand (e.g. an admin endpoint, a future phase).
     *
     * <p>Roadmap #11 — pinned notes are excluded via
     * {@link MemoryNoteRepository#findByStatusAndExpiresAtBeforeAndPinnedFalse}
     * so a "pin this" never gets swept away by its own TTL.</p>
     *
     * @return the number of notes forgotten
     */
    public int cleanupExpiredNotes() {
        Instant start = clock.instant();
        try {
            List<MemoryNoteEntity> expired =
                    repository.findByStatusAndExpiresAtBeforeAndPinnedFalse("ACTIVE", clock.instant());
            for (MemoryNoteEntity note : expired) {
                forgetService.forget(note.getMemoryId(), "system", "ttl-expired");
            }
            metrics.cleanupRun("success");
            metrics.cleanupExpired(expired.size());
            return expired.size();
        } catch (RuntimeException e) {
            metrics.cleanupRun("failure");
            throw e;
        } finally {
            metrics.recordCleanupDuration(Duration.between(start, clock.instant()));
        }
    }
}
