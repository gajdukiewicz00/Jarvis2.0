package org.jarvis.vision.phase10;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;

/**
 * Phase 10 — periodic cleaner of raw vision artifacts.
 *
 * <p>Walks the configured root (default
 * {@code ~/.jarvis/data/vision-security/users}) and deletes regular
 * files whose last-modified time is older than {@code retention.days}.
 * Empty directories left behind are also removed. Disabled mode keeps
 * the scheduler bean but exits the sweep early — useful for tests and
 * non-owner hosts.</p>
 *
 * <p>Emits a single {@link org.jarvis.events.AuditEventType#VISION_FRAMES_PURGED}
 * event per sweep with {@code filesDeleted}, {@code bytesFreed}, and
 * {@code retentionDays}; no individual frame paths are sent over the
 * bus, so log/analytics consumers don't see private filenames.</p>
 */
@Slf4j
@Component
@EnableScheduling
public class RawFrameRetentionScheduler {

    private final VisionRetentionProperties properties;
    private final VisionEventEmitter emitter;

    public RawFrameRetentionScheduler(VisionRetentionProperties properties,
                                      VisionEventEmitter emitter) {
        this.properties = properties;
        this.emitter = emitter;
    }

    @Scheduled(fixedDelayString = "${jarvis.vision.frame-retention.sweep-interval-ms:3600000}")
    public void sweep() {
        if (!properties.isEnabled()) {
            log.debug("vision retention disabled — skipping sweep");
            return;
        }
        Path root = Paths.get(properties.getRoot());
        if (!Files.isDirectory(root)) {
            log.debug("vision retention root not present yet: {}", root);
            return;
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(properties.getDays()));
        SweepResult result = sweepInternal(root, cutoff);
        log.info("vision retention sweep done: deleted={} bytesFreed={} (cutoff={}d)",
                result.filesDeleted, result.bytesFreed, properties.getDays());
        emitter.framesPurged(result.filesDeleted, result.bytesFreed, properties.getDays());
    }

    /** Visible for tests. */
    SweepResult sweepInternal(Path root, Instant cutoff) {
        SweepResult result = new SweepResult();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    Instant modified = attrs.lastModifiedTime().toInstant();
                    if (modified.isBefore(cutoff)) {
                        long size = attrs.size();
                        try {
                            Files.delete(file);
                            result.filesDeleted++;
                            result.bytesFreed += size;
                        } catch (IOException ex) {
                            log.debug("retention: could not delete {} — {}", file, ex.getMessage());
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    if (dir.equals(root)) return FileVisitResult.CONTINUE;
                    try (var stream = Files.list(dir)) {
                        if (stream.findAny().isEmpty()) {
                            Files.delete(dir);
                        }
                    } catch (IOException ignored) {
                        // best-effort directory cleanup
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ex) {
            log.warn("vision retention walk failed: {}", ex.getMessage());
        }
        return result;
    }

    static final class SweepResult {
        int filesDeleted;
        long bytesFreed;
    }
}
