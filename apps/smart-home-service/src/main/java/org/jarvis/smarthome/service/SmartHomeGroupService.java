package org.jarvis.smarthome.service;

import org.jarvis.smarthome.model.SmartHomeGroup;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime registry of device groups (in-memory). A group is an arbitrary,
 * possibly-overlapping collection of device ids that can be acted on together.
 */
@Service
public class SmartHomeGroupService {

    private final Map<String, SmartHomeGroup> groups = new ConcurrentHashMap<>();

    /** Create or fully replace a group. */
    public SmartHomeGroup save(SmartHomeGroup group) {
        groups.put(group.id(), group);
        return group;
    }

    public List<SmartHomeGroup> all() {
        return List.copyOf(groups.values());
    }

    public Optional<SmartHomeGroup> find(String groupId) {
        return Optional.ofNullable(groups.get(groupId));
    }

    public boolean remove(String groupId) {
        return groups.remove(groupId) != null;
    }

    /** Add a device to an existing group. Empty if the group does not exist. */
    public Optional<SmartHomeGroup> addDevice(String groupId, String deviceId) {
        return Optional.ofNullable(groups.computeIfPresent(groupId, (id, group) -> appendDevice(group, deviceId)));
    }

    /** Remove a device from an existing group. Empty if the group does not exist. */
    public Optional<SmartHomeGroup> removeDevice(String groupId, String deviceId) {
        return Optional.ofNullable(groups.computeIfPresent(groupId, (id, group) -> withoutDevice(group, deviceId)));
    }

    private static SmartHomeGroup appendDevice(SmartHomeGroup group, String deviceId) {
        if (group.deviceIds().contains(deviceId)) {
            return group;
        }
        List<String> updated = new ArrayList<>(group.deviceIds());
        updated.add(deviceId);
        return new SmartHomeGroup(group.id(), group.name(), updated);
    }

    private static SmartHomeGroup withoutDevice(SmartHomeGroup group, String deviceId) {
        return new SmartHomeGroup(group.id(), group.name(),
                group.deviceIds().stream().filter(id -> !id.equals(deviceId)).toList());
    }
}
