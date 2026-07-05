package org.jarvis.smarthome.service;

import org.jarvis.smarthome.model.SmartHomeRoom;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime registry of rooms (in-memory). Unlike a device group, room
 * assignment is exclusive: assigning a device to a room removes it from any
 * other room it previously belonged to.
 */
@Service
public class SmartHomeRoomService {

    private final Map<String, SmartHomeRoom> rooms = new ConcurrentHashMap<>();

    /** Create a room, or rename it while preserving its current device assignments. */
    public synchronized SmartHomeRoom createOrUpdate(String roomId, String name) {
        SmartHomeRoom existing = rooms.get(roomId);
        List<String> deviceIds = existing == null ? List.of() : existing.deviceIds();
        SmartHomeRoom room = new SmartHomeRoom(roomId, name, deviceIds);
        rooms.put(roomId, room);
        return room;
    }

    public List<SmartHomeRoom> all() {
        return List.copyOf(rooms.values());
    }

    public Optional<SmartHomeRoom> find(String roomId) {
        return Optional.ofNullable(rooms.get(roomId));
    }

    public synchronized boolean remove(String roomId) {
        return rooms.remove(roomId) != null;
    }

    /**
     * Assign a device to a room, unassigning it from any other room first.
     * Empty if the target room does not exist.
     */
    public synchronized Optional<SmartHomeRoom> assignDevice(String roomId, String deviceId) {
        if (!rooms.containsKey(roomId)) {
            return Optional.empty();
        }
        rooms.replaceAll((id, room) -> id.equals(roomId) ? room : withoutDevice(room, deviceId));
        SmartHomeRoom updated = withDevice(rooms.get(roomId), deviceId);
        rooms.put(roomId, updated);
        return Optional.of(updated);
    }

    /** Unassign a device from a room. Empty if the room does not exist. */
    public synchronized Optional<SmartHomeRoom> unassignDevice(String roomId, String deviceId) {
        SmartHomeRoom room = rooms.get(roomId);
        if (room == null) {
            return Optional.empty();
        }
        SmartHomeRoom updated = withoutDevice(room, deviceId);
        rooms.put(roomId, updated);
        return Optional.of(updated);
    }

    private static SmartHomeRoom withDevice(SmartHomeRoom room, String deviceId) {
        if (room.deviceIds().contains(deviceId)) {
            return room;
        }
        List<String> updated = new ArrayList<>(room.deviceIds());
        updated.add(deviceId);
        return new SmartHomeRoom(room.id(), room.name(), updated);
    }

    private static SmartHomeRoom withoutDevice(SmartHomeRoom room, String deviceId) {
        return new SmartHomeRoom(room.id(), room.name(),
                room.deviceIds().stream().filter(id -> !id.equals(deviceId)).toList());
    }
}
