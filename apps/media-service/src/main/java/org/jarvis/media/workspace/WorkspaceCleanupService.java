package org.jarvis.media.workspace;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.media.config.MediaProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;

/**
 * Reclaims disk space by deleting per-job workspace sub-directories that have not
 * been touched in longer than the configured TTL ({@code media.workspace.artifact-ttl-hours},
 * default 24h). Every job writes its artifacts under a single
 * {@code <workspace>/<workId>/} directory (see {@link WorkspaceManager#newWorkId()}),
 * so "no file in this directory has changed recently" is a reliable proxy for "this
 * job's output is no longer needed" — job lifecycle/records themselves live in the
 * separate {@code MediaJobStore}, not on disk, and are unaffected by this cleanup.
 *
 * <p>Deletion is strictly confined to first-level children of the workspace root
 * ({@link WorkspaceManager#workspaceDir()}) — nothing outside it, and nothing above
 * it, is ever touched, mirroring the trust boundary {@link WorkspaceManager} already
 * enforces for reads and writes.</p>
 */
@Slf4j
@Component
public class WorkspaceCleanupService {

    private final WorkspaceManager workspace;
    private final MediaProperties props;
    private final Clock clock;

    public WorkspaceCleanupService(WorkspaceManager workspace, MediaProperties props, Clock clock) {
        this.workspace = workspace;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${media.workspace.cleanup-interval-ms:1800000}",
            initialDelayString = "${media.workspace.cleanup-initial-delay-ms:1800000}")
    public void scheduledCleanup() {
        if (!props.enabled()) {
            return;
        }
        int removed = cleanupExpiredArtifacts();
        if (removed > 0) {
            log.info("Workspace cleanup removed {} expired artifact director{}", removed, removed == 1 ? "y" : "ies");
        }
    }

    /**
     * Deletes every first-level workspace sub-directory whose most recent file
     * activity is older than the configured TTL. Returns the number of directories
     * removed. Pure enough to call directly from tests (with an injected fixed
     * {@link Clock}) as well as from the scheduled trigger above.
     */
    public int cleanupExpiredArtifacts() {
        Duration ttl = Duration.ofHours(Math.max(0, props.workspace().artifactTtlHours()));
        Instant cutoff = clock.instant().minus(ttl);
        Path root = workspace.workspaceDir();
        int removed = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path child : stream) {
                if (!Files.isDirectory(child)) {
                    continue;
                }
                if (latestModification(child).isBefore(cutoff)) {
                    deleteRecursively(child);
                    removed++;
                }
            }
        } catch (IOException e) {
            log.warn("Workspace cleanup could not scan {}: {}", root, e.getMessage());
        }
        return removed;
    }

    private Instant latestModification(Path dir) {
        try (var files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile)
                    .map(this::mtimeOrEpoch)
                    .max(Instant::compareTo)
                    .orElseGet(() -> mtimeOrEpoch(dir));
        } catch (IOException e) {
            return mtimeOrEpoch(dir);
        }
    }

    private Instant mtimeOrEpoch(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException e) {
            return Instant.EPOCH;
        }
    }

    private void deleteRecursively(Path dir) {
        try (var files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(this::deleteQuietly);
        } catch (IOException e) {
            log.warn("Could not walk {} for deletion: {}", dir, e.getMessage());
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Could not delete {}: {}", path, e.getMessage());
        }
    }
}
