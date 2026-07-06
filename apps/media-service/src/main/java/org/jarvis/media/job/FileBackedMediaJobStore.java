package org.jarvis.media.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * File-backed job store: persists every {@link MediaJob} as its own JSON file so jobs
 * survive a pod restart, without introducing a database. All jobs are loaded into an
 * in-memory cache on construction and every {@link #save} writes through to disk
 * immediately. This is the effective default job store (no {@code
 * jarvis.media.job-store} property needs to be set) so job history is durable
 * out of the box, mirroring the agent-service task-store pattern; set {@code
 * jarvis.media.job-store=memory} to opt back into the ephemeral {@link
 * InMemoryMediaJobStore}, or {@code =postgres} for a store shared across replicas.
 */
@Slf4j
@Repository
@ConditionalOnProperty(name = "jarvis.media.job-store", havingValue = "file", matchIfMissing = true)
public class FileBackedMediaJobStore implements MediaJobStore {

    private final Path dir;
    private final ObjectMapper mapper;
    private final Map<String, MediaJob> jobs = new ConcurrentHashMap<>();

    public FileBackedMediaJobStore(
            ObjectMapper mapper,
            @Value("${jarvis.media.job-store.dir:/tmp/jarvis-media-jobs}") String dir) {
        this.mapper = mapper;
        this.dir = Path.of(dir).toAbsolutePath().normalize();
        init();
    }

    private void init() {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create media job store dir at " + dir, e);
        }
        load();
    }

    private void load() {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : stream) {
                try {
                    MediaJob job = mapper.readValue(file.toFile(), MediaJob.class);
                    jobs.put(job.id(), job);
                } catch (IOException e) {
                    log.warn("Skipping unreadable media job file {}: {}", file.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read media job store dir at " + dir, e);
        }
        log.info("Loaded {} media job(s) from {}", jobs.size(), dir);
    }

    @Override
    public MediaJob save(MediaJob job) {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(file(job.id()).toFile(), job);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot persist media job " + job.id(), e);
        }
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

    private Path file(String jobId) {
        return dir.resolve(jobId + ".json");
    }
}
