package org.jarvis.media.job;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory job store. Jobs are ephemeral and reset on restart — an
 * explicit opt-in for callers that genuinely want a stateless store (e.g. a
 * throwaway dev container with no writable volume). {@link FileBackedMediaJobStore}
 * is the effective default (see its javadoc): it persists every job to disk so work
 * survives a pod restart without requiring a database. Set
 * {@code jarvis.media.job-store=memory} explicitly to opt into this store instead.
 */
@Repository
@ConditionalOnProperty(name = "jarvis.media.job-store", havingValue = "memory")
public class InMemoryMediaJobStore implements MediaJobStore {

    private final Map<String, MediaJob> jobs = new ConcurrentHashMap<>();

    @Override
    public MediaJob save(MediaJob job) {
        jobs.put(job.id(), job);
        return job;
    }

    @Override
    public Optional<MediaJob> findById(String id) {
        return Optional.ofNullable(jobs.get(id));
    }

    @Override
    public List<MediaJob> findByUser(String userId) {
        return jobs.values().stream()
                .filter(j -> j.userId() != null && j.userId().equals(userId))
                .sorted(Comparator.comparing(MediaJob::createdAt).reversed())
                .toList();
    }
}
