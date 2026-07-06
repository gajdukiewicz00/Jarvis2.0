package org.jarvis.media.web;

import org.jarvis.media.config.MediaProperties;
import org.jarvis.media.job.FileBackedMediaJobStore;
import org.jarvis.media.job.InMemoryMediaJobStore;
import org.jarvis.media.job.MediaJobStore;
import org.jarvis.media.job.PostgresMediaJobStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only introspection endpoint: reports whether the service is enabled, which
 * job store implementation is currently wired, and whether each media provider is
 * running in {@code mock} or a real/native mode. Deliberately does not call {@link
 * MediaFeatureGate#ensureEnabled()} — the whole point is to let a caller see the
 * {@code enabled} flag (and every provider mode) even while the feature flag is off.
 * This is global, non-user-scoped operational metadata (no per-user data, no
 * secrets), so unlike the job endpoints it does not require a resolved user id —
 * the standard service security filter chain (see {@code SecurityConfig}) still
 * requires the caller to be authenticated.
 */
@RestController
@RequestMapping("/api/v1/media")
public class MediaStatusController {

    private final MediaProperties props;
    private final MediaJobStore jobStore;

    public MediaStatusController(MediaProperties props, MediaJobStore jobStore) {
        this.props = props;
        this.jobStore = jobStore;
    }

    @GetMapping("/status")
    public MediaStatusView status() {
        return MediaStatusView.of(props, jobStoreMode(jobStore));
    }

    /**
     * Maps the wired {@link MediaJobStore} bean to its {@code jarvis.media.job-store}
     * mode name. Kept here (rather than as an interface method) so the persistence
     * boundary stays a plain save/find contract with no reporting concern grafted on.
     */
    private String jobStoreMode(MediaJobStore store) {
        return switch (store) {
            case InMemoryMediaJobStore ignored -> "memory";
            case FileBackedMediaJobStore ignored -> "file";
            case PostgresMediaJobStore ignored -> "postgres";
            default -> "unknown";
        };
    }
}
