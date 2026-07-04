package org.jarvis.media.job;

import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory job store. The MVP intentionally avoids a database so the
 * service stays isolated (no cross-service network policy, no migrations). Jobs are
 * ephemeral and reset on restart — documented as a known limitation.
 */
@Repository
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
