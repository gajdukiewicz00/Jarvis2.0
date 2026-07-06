package org.jarvis.syncservice.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.sync.crypto.SessionKeys;
import org.jarvis.syncservice.config.SyncServiceProperties;
import org.jarvis.syncservice.domain.PairedDevice;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 12-bis — file-backed pairing store.
 *
 * <p>Supersedes {@link InMemoryPairingStore}: a sync-service restart (redeploy,
 * crash, node drain) used to silently drop every paired Android device, forcing
 * a full re-pair. Paired-device records — including the derived per-session
 * ChaCha20-Poly1305 key — are now written to {@link SyncServiceProperties#getPairedDevicesPath()}
 * so they survive a restart.</p>
 *
 * <p><b>Secrets handling:</b> the session key and identity/kex public material are
 * secret or sensitive AEAD inputs. They are persisted (there is no other durable
 * place to keep them short of forcing every phone to re-pair on every restart) but
 * are NEVER written to the application log, and the backing file is created with
 * owner-only permissions ({@code 0600}) on POSIX filesystems — the same convention
 * already used for {@code server-key-path}.</p>
 *
 * <p><b>Fail-soft:</b> if the configured path is not writable (missing persistent
 * volume, read-only filesystem, permission denied in a local/test environment),
 * pairing keeps working for the lifetime of the process but silently falls back to
 * in-memory-only behavior — the service degrades to the pre-fix behavior instead of
 * failing to start.</p>
 */
@Slf4j
@Component
public class FilePairingStore implements PairingStore {

    private final ConcurrentHashMap<String, PairedDevice> byDeviceId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PairedDevice> byRoutingId = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final Path storePath;
    private final Object fileLock = new Object();
    private volatile boolean diskAvailable = true;

    public FilePairingStore(SyncServiceProperties props) {
        this.storePath = Paths.get(props.getPairedDevicesPath());
        loadFromDisk();
    }

    @Override
    public PairedDevice save(PairedDevice device) {
        byDeviceId.put(device.deviceId(), device);
        byRoutingId.put(device.routingId(), device);
        persist();
        return device;
    }

    @Override
    public Optional<PairedDevice> findByDeviceId(String deviceId) {
        return Optional.ofNullable(byDeviceId.get(deviceId));
    }

    @Override
    public Optional<PairedDevice> findByRoutingId(String routingId) {
        return Optional.ofNullable(byRoutingId.get(routingId));
    }

    @Override
    public int size() {
        return byDeviceId.size();
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
                List<PersistedRecord> records = mapper.readValue(json, new TypeReference<List<PersistedRecord>>() { });
                for (PersistedRecord r : records) {
                    PairedDevice device = r.toDevice();
                    byDeviceId.put(device.deviceId(), device);
                    byRoutingId.put(device.routingId(), device);
                }
                log.info("loaded {} paired device(s) from {}", byDeviceId.size(), storePath);
            } catch (IOException | RuntimeException e) {
                // Corrupt or unreadable pairing file: start empty rather than refusing to
                // boot the service; affected devices simply re-pair.
                log.warn("failed to load persisted pairing store from {}: {}", storePath, e.getMessage());
            }
        }
    }

    private void persist() {
        if (!diskAvailable) {
            return;
        }
        synchronized (fileLock) {
            try {
                Path parent = storePath.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                List<PersistedRecord> records = new ArrayList<>();
                for (PairedDevice device : byDeviceId.values()) {
                    records.add(PersistedRecord.fromDevice(device));
                }
                byte[] json = mapper.writeValueAsBytes(records);

                Path tmp = Files.createTempFile(parent, "paired-devices", ".tmp");
                try {
                    Files.write(tmp, json, StandardOpenOption.TRUNCATE_EXISTING);
                    restrictPermissions(tmp);
                    Files.move(tmp, storePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } finally {
                    Files.deleteIfExists(tmp);
                }
            } catch (IOException e) {
                diskAvailable = false;
                log.warn("persistent pairing store unavailable at {}; falling back to in-memory-only: {}",
                        storePath, e.getMessage());
            }
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

    /** JSON-friendly mirror of {@link PairedDevice}. All key material is base64-encoded;
     * nothing in this record is ever logged. */
    private record PersistedRecord(
            String deviceId, String deviceLabel,
            String identityPubB64, String devicePubKexB64, String serverPubKexB64,
            String sessionKeyB64, String routingId, Instant pairedAt, Instant lastSeen) {

        static PersistedRecord fromDevice(PairedDevice d) {
            return new PersistedRecord(
                    d.deviceId(), d.deviceLabel(),
                    Base64.getEncoder().encodeToString(d.identityPub()),
                    Base64.getEncoder().encodeToString(d.devicePubKex()),
                    Base64.getEncoder().encodeToString(d.serverPubKex()),
                    Base64.getEncoder().encodeToString(d.sessionKey().key()),
                    d.routingId(), d.pairedAt(), d.lastSeen());
        }

        PairedDevice toDevice() {
            return new PairedDevice(deviceId, deviceLabel,
                    Base64.getDecoder().decode(identityPubB64),
                    Base64.getDecoder().decode(devicePubKexB64),
                    Base64.getDecoder().decode(serverPubKexB64),
                    new SessionKeys(Base64.getDecoder().decode(sessionKeyB64)),
                    routingId, pairedAt, lastSeen);
        }
    }
}
