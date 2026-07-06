package org.jarvis.smarthome.history;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeviceStateHistoryRepository extends JpaRepository<DeviceStateHistoryEntry, Long> {

    List<DeviceStateHistoryEntry> findByDeviceIdOrderByRecordedAtDesc(String deviceId, Pageable pageable);
}
