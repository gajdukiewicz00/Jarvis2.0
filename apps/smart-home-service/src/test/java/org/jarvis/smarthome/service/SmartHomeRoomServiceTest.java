package org.jarvis.smarthome.service;

import org.jarvis.smarthome.model.SmartHomeRoom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmartHomeRoomServiceTest {

    private SmartHomeRoomService roomService;

    @BeforeEach
    void setUp() {
        roomService = new SmartHomeRoomService();
    }

    @Test
    void createOrUpdateCreatesNewEmptyRoom() {
        SmartHomeRoom room = roomService.createOrUpdate("kitchen", "Kitchen");

        assertEquals("kitchen", room.id());
        assertEquals("Kitchen", room.name());
        assertTrue(room.deviceIds().isEmpty());
        assertEquals(1, roomService.all().size());
    }

    @Test
    void createOrUpdateRenamesRoomWithoutLosingDeviceAssignments() {
        roomService.createOrUpdate("kitchen", "Kitchen");
        roomService.assignDevice("kitchen", "kitchen_light");

        SmartHomeRoom renamed = roomService.createOrUpdate("kitchen", "Kitchen Nook");

        assertEquals("Kitchen Nook", renamed.name());
        assertEquals(List.of("kitchen_light"), renamed.deviceIds());
    }

    @Test
    void findReturnsEmptyForUnknownRoom() {
        assertTrue(roomService.find("missing").isEmpty());
    }

    @Test
    void removeDeletesRoomAndReturnsTrue() {
        roomService.createOrUpdate("kitchen", "Kitchen");

        assertTrue(roomService.remove("kitchen"));
        assertTrue(roomService.find("kitchen").isEmpty());
    }

    @Test
    void removeReturnsFalseForUnknownRoom() {
        assertFalse(roomService.remove("missing"));
    }

    @Test
    void assignDeviceAddsDeviceToRoom() {
        roomService.createOrUpdate("kitchen", "Kitchen");

        Optional<SmartHomeRoom> updated = roomService.assignDevice("kitchen", "kitchen_light");

        assertTrue(updated.isPresent());
        assertEquals(List.of("kitchen_light"), updated.get().deviceIds());
    }

    @Test
    void assignDeviceReturnsEmptyForUnknownRoom() {
        assertTrue(roomService.assignDevice("missing", "kitchen_light").isEmpty());
    }

    @Test
    void assignDeviceIsExclusiveAcrossRooms() {
        roomService.createOrUpdate("kitchen", "Kitchen");
        roomService.createOrUpdate("office", "Office");
        roomService.assignDevice("kitchen", "desk_lamp");

        roomService.assignDevice("office", "desk_lamp");

        assertTrue(roomService.find("kitchen").get().deviceIds().isEmpty());
        assertEquals(List.of("desk_lamp"), roomService.find("office").get().deviceIds());
    }

    @Test
    void assignDeviceTwiceToSameRoomDoesNotDuplicate() {
        roomService.createOrUpdate("kitchen", "Kitchen");

        roomService.assignDevice("kitchen", "kitchen_light");
        SmartHomeRoom updated = roomService.assignDevice("kitchen", "kitchen_light").get();

        assertEquals(List.of("kitchen_light"), updated.deviceIds());
    }

    @Test
    void unassignDeviceRemovesItFromRoom() {
        roomService.createOrUpdate("kitchen", "Kitchen");
        roomService.assignDevice("kitchen", "kitchen_light");

        Optional<SmartHomeRoom> updated = roomService.unassignDevice("kitchen", "kitchen_light");

        assertTrue(updated.isPresent());
        assertTrue(updated.get().deviceIds().isEmpty());
    }

    @Test
    void unassignDeviceReturnsEmptyForUnknownRoom() {
        assertTrue(roomService.unassignDevice("missing", "kitchen_light").isEmpty());
    }
}
