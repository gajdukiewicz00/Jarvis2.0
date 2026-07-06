package org.jarvis.smarthome.history;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeviceStateHistoryRepository extends JpaRepository<DeviceStateHistoryEntry, Long> {

    /** Scoped to the authenticated user so one user's history cannot leak another's. */
    List<DeviceStateHistoryEntry> findByUserIdAndDeviceIdOrderByRecordedAtDesc(
            String userId, String deviceId, Pageable pageable);
}
