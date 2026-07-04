package org.jarvis.media.job;

import java.util.List;
import java.util.Optional;

/** Persistence boundary for media jobs (in-memory for the MVP; swappable later). */
public interface MediaJobStore {

    MediaJob save(MediaJob job);

    Optional<MediaJob> findById(String id);

    List<MediaJob> findByUser(String userId);
}
