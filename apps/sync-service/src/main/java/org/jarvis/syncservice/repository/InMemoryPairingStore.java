package org.jarvis.syncservice.repository;

import org.jarvis.syncservice.domain.PairedDevice;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 12 — volatile in-memory pairing store.
 *
 * <p>Process-local; acceptable for the diploma demo (one operator, one
 * desktop). A JPA implementation will replace this in Phase 12-bis when
 * sync-service runs on k8s with multiple replicas. Don't add features
 * here that wouldn't survive that swap.</p>
 */
@Component
@ConditionalOnMissingBean(value = PairingStore.class, ignored = InMemoryPairingStore.class)
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
