package org.jarvis.memory.obsidian;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
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

    public MemoryExpiryCleanupService(MemoryNoteRepository repository,
                                      MemoryForgetService forgetService,
                                      MemoryExpiryProperties properties,
                                      Clock clock) {
        this.repository = repository;
        this.forgetService = forgetService;
        this.properties = properties;
        this.clock = clock;
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
     * Forgets every ACTIVE note whose {@code expiresAt} is in the past.
     * Public (not just scheduler-invoked) so it is directly unit-testable
     * and callable on-demand (e.g. an admin endpoint, a future phase).
     *
     * @return the number of notes forgotten
     */
    public int cleanupExpiredNotes() {
        List<MemoryNoteEntity> expired = repository.findByStatusAndExpiresAtBefore("ACTIVE", clock.instant());
        for (MemoryNoteEntity note : expired) {
            forgetService.forget(note.getMemoryId(), "system", "ttl-expired");
        }
        return expired.size();
    }
}
