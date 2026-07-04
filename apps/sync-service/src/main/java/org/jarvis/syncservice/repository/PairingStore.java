package org.jarvis.syncservice.repository;

import org.jarvis.syncservice.domain.PairedDevice;

import java.util.Optional;

/**
 * Phase 12 — pairing persistence boundary.
 *
 * <p>Pass 1 ships an in-memory implementation suitable for the diploma
 * demo. The interface is here so a JPA-backed implementation can drop in
 * (Phase 12-bis) without touching call sites.</p>
 */
public interface PairingStore {
    PairedDevice save(PairedDevice device);
    Optional<PairedDevice> findByDeviceId(String deviceId);
    Optional<PairedDevice> findByRoutingId(String routingId);
    int size();
}
