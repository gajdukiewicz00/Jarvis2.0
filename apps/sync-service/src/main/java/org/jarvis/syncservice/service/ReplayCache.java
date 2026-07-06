package org.jarvis.syncservice.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.syncservice.config.SyncServiceProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 12-bis — application-level replay protection on top of AEAD nonces,
 * now persisted so a sync-service restart does not reset the replay window.
 *
 * <p>ChaCha20-Poly1305 fails authentication on nonce reuse <em>only</em>
 * if the same key+nonce is used to encrypt two different plaintexts —
 * but a network attacker who replays an unmodified blob would not be
 * caught by the AEAD itself. This cache rejects the second sighting of
 * the same {@code (deviceId, nonce)} pair within a 24h sliding window,
 * including across a restart: previously an in-memory-only Caffeine cache
 * meant an attacker who captured a blob before a crash/redeploy could
 * replay it successfully for up to the remainder of that 24h TTL.</p>
 *
 * <p><b>Fail-soft:</b> if {@link SyncServiceProperties#getReplayCacheStorePath()}
 * is not writable, the cache keeps rejecting replays for the lifetime of the
 * process but silently falls back to in-memory-only (the pre-fix behavior)
 * rather than failing to start.</p>
 */
@Slf4j
@Component
public class ReplayCache {

    private static final Duration TTL = Duration.ofHours(24);

    private final ConcurrentHashMap<String, Instant> seenAt = new ConcurrentHashMap<>();
    private final long capacity;
    private final Path storePath;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final Object fileLock = new Object();
    private volatile boolean diskAvailable = true;

    @Autowired
    public ReplayCache(SyncServiceProperties props) {
        this(props, Clock.systemUTC());
    }

    /** Package-visible so tests can simulate TTL expiry and restarts deterministically
     * instead of sleeping for 24 real hours. */
    ReplayCache(SyncServiceProperties props, Clock clock) {
        // Total cap = per-device cap × ~16 paired devices upper bound; coarse
        // cap is fine because real volume per device per hour is tiny.
        this.capacity = (long) props.getReplayCacheSizePerDevice() * 16;
        this.clock = clock;
        this.storePath = Paths.get(props.getReplayCacheStorePath());
        loadFromDisk();
    }

    /** @return true if the nonce was unseen (and now recorded, in memory and on
     * disk); false if it was already seen — either earlier this process or in a
     * previous one, restored from {@link #storePath} on construction. */
    public boolean recordIfUnseen(String deviceId, String nonceB64) {
        String key = deviceId + "|" + nonceB64;
        synchronized (fileLock) {
            pruneExpired();
            // Atomic check-and-record under fileLock: closes the TOCTOU window where
            // two concurrent replays of the same nonce both pass, and serializes the
            // file write below so concurrent persists never race on the same path.
            Instant previous = seenAt.putIfAbsent(key, clock.instant());
            if (previous != null) {
                return false;
            }
            enforceCapacity();
            persist();
            return true;
        }
    }

    public int size() {
        return seenAt.size();
    }

    private void pruneExpired() {
        Instant cutoff = clock.instant().minus(TTL);
        seenAt.entrySet().removeIf(e -> e.getValue().isBefore(cutoff));
    }

    /** Evicts the oldest entries once the coarse cap is exceeded; an attacker flooding
     * distinct nonces cannot grow the persisted map without bound. */
    private void enforceCapacity() {
        if (seenAt.size() <= capacity) {
            return;
        }
        seenAt.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .limit(seenAt.size() - capacity)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(seenAt::remove);
    }

    private void loadFromDisk() {
        synchronized (fileLock) {
            if (!Files.exists(storePath)) {
                return;
            }
            try {
                byte[] json = Files.readAllBytes(storePath);
                if (json.length == 0) {
                    return;
                }
                Map<String, Instant> loaded = mapper.readValue(json, new TypeReference<Map<String, Instant>>() { });
                Instant cutoff = clock.instant().minus(TTL);
                loaded.forEach((k, v) -> {
                    if (v.isAfter(cutoff)) {
                        seenAt.put(k, v);
                    }
                });
                log.info("loaded {} replay-protected nonce(s) from {}", seenAt.size(), storePath);
            } catch (IOException | RuntimeException e) {
                // Corrupt or unreadable replay-cache file: start empty rather than
                // refusing to boot the service, or (worse) treating every nonce as
                // already-seen and rejecting all sync traffic.
                log.warn("failed to load persisted replay cache from {}: {}", storePath, e.getMessage());
            }
        }
    }

    private void persist() {
        if (!diskAvailable) {
            return;
        }
        try {
            Path parent = storePath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            byte[] json = mapper.writeValueAsBytes(new TreeMap<>(seenAt));

            Path tmp = Files.createTempFile(parent, "replay-cache", ".tmp");
            try {
                Files.write(tmp, json, StandardOpenOption.TRUNCATE_EXISTING);
                restrictPermissions(tmp);
                Files.move(tmp, storePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            diskAvailable = false;
            log.warn("persistent replay cache unavailable at {}; falling back to in-memory-only: {}",
                    storePath, e.getMessage());
        }
    }

    private static void restrictPermissions(Path path) {
        try {
            if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
            }
        } catch (IOException ignored) {
            // Best-effort; never block persistence on a permissions tweak failing.
        }
    }
}
