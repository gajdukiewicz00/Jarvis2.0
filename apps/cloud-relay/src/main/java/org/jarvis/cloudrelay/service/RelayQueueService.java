package org.jarvis.cloudrelay.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.cloudrelay.RelayProperties;
import org.jarvis.cloudrelay.domain.OpaqueBlob;
import org.jarvis.cloudrelay.domain.OpaqueBlob.Direction;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Phase 12 — per-routingId, per-direction blob queues.
 *
 * <p>Two FIFO queues per routingId: TO_HOME (phone → sync-service) and
 * TO_DEVICE (sync-service → phone). Each queue is capped to
 * {@code queueCap}; the oldest blob is evicted on overflow. Blobs older
 * than {@code blobTtlSeconds} are skipped on dequeue. Whole queues evict
 * after {@code queueIdleTtlSeconds} of inactivity.</p>
 */
@Slf4j
@Service
public class RelayQueueService {

    private final RelayProperties props;
    private final Cache<String, ConcurrentLinkedDeque<OpaqueBlob>> queues;

    public RelayQueueService(RelayProperties props) {
        this.props = props;
        this.queues = Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofSeconds(props.getQueueIdleTtlSeconds()))
                .maximumSize(10_000)
                .build();
    }

    public OpaqueBlob enqueue(String routingId, Direction direction, byte[] payload) {
        if (payload.length > props.getMaxBlobBytes()) {
            throw new BlobTooLargeException(payload.length, props.getMaxBlobBytes());
        }
        var queue = queues.get(key(routingId, direction), k -> new ConcurrentLinkedDeque<>());
        OpaqueBlob blob = new OpaqueBlob(direction, payload, Instant.now());
        queue.add(blob);
        while (queue.size() > props.getQueueCap()) {
            queue.pollFirst();
        }
        return blob;
    }

    /**
     * Dequeue and return up to {@code limit} blobs for {@code (routingId, direction)},
     * skipping any that have aged past {@code blobTtlSeconds}.
     */
    public List<OpaqueBlob> drain(String routingId, Direction direction, int limit) {
        var queue = queues.getIfPresent(key(routingId, direction));
        if (queue == null || queue.isEmpty()) return List.of();
        List<OpaqueBlob> out = new ArrayList<>();
        Instant cutoff = Instant.now().minusSeconds(props.getBlobTtlSeconds());
        while (out.size() < limit) {
            OpaqueBlob b = queue.pollFirst();
            if (b == null) break;
            if (b.storedAt().isBefore(cutoff)) continue;
            out.add(b);
        }
        return out;
    }

    /** Inspect (do NOT consume) the queue size — health diagnostic only. */
    public int queueSize(String routingId, Direction direction) {
        var q = queues.getIfPresent(key(routingId, direction));
        return q == null ? 0 : q.size();
    }

    public Optional<OpaqueBlob> peekFirst(String routingId, Direction direction) {
        var q = queues.getIfPresent(key(routingId, direction));
        return q == null ? Optional.empty() : Optional.ofNullable(q.peekFirst());
    }

    private static String key(String routingId, Direction d) {
        return routingId + "::" + d.name();
    }

    public static final class BlobTooLargeException extends RuntimeException {
        public BlobTooLargeException(int actual, int max) {
            super("blob size " + actual + " > limit " + max);
        }
    }
}
