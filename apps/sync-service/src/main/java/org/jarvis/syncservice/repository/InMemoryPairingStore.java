package org.jarvis.syncservice.repository;

import org.jarvis.syncservice.domain.PairedDevice;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 12 — volatile in-memory pairing store.
 *
 * <p>Superseded in production by {@link FilePairingStore} (Phase 12-bis),
 * which is the only {@code @Component}-registered {@link PairingStore}
 * implementation, so a sync-service restart no longer drops every paired
 * device. This class is intentionally not Spring-managed anymore — it is
 * kept as a lightweight, dependency-free {@link PairingStore} for unit
 * tests that don't need file persistence (see {@code PairingServiceTest},
 * {@code BlobInboxServiceTest}).</p>
 */
public class InMemoryPairingStore implements PairingStore {

    private final ConcurrentHashMap<String, PairedDevice> byDeviceId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PairedDevice> byRoutingId = new ConcurrentHashMap<>();

    @Override
    public PairedDevice save(PairedDevice device) {
        byDeviceId.put(device.deviceId(), device);
        byRoutingId.put(device.routingId(), device);
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
}
