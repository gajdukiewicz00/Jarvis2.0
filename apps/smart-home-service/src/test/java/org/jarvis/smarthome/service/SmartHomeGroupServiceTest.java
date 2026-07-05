package org.jarvis.smarthome.service;

import org.jarvis.smarthome.model.SmartHomeGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmartHomeGroupServiceTest {

    private SmartHomeGroupService groupService;

    @BeforeEach
    void setUp() {
        groupService = new SmartHomeGroupService();
    }

    @Test
    void saveStoresGroupAndAllReturnsIt() {
        SmartHomeGroup group = new SmartHomeGroup("downstairs", "Downstairs",
                List.of("kitchen_light", "desk_lamp"));

        SmartHomeGroup saved = groupService.save(group);

        assertEquals(group, saved);
        assertEquals(1, groupService.all().size());
    }

    @Test
    void saveDeduplicatesDeviceIds() {
        SmartHomeGroup group = new SmartHomeGroup("lights", "Lights",
                List.of("kitchen_light", "kitchen_light", "desk_lamp"));

        SmartHomeGroup saved = groupService.save(group);

        assertEquals(List.of("kitchen_light", "desk_lamp"), saved.deviceIds());
    }

    @Test
    void saveReplacesExistingGroupWithSameId() {
        groupService.save(new SmartHomeGroup("lights", "Lights", List.of("kitchen_light")));
        SmartHomeGroup replacement = new SmartHomeGroup("lights", "All Lights",
                List.of("kitchen_light", "desk_lamp"));

        groupService.save(replacement);

        assertEquals(1, groupService.all().size());
        assertEquals(replacement, groupService.find("lights").get());
    }

    @Test
    void findReturnsEmptyForUnknownGroup() {
        assertTrue(groupService.find("missing").isEmpty());
    }

    @Test
    void removeDeletesGroupAndReturnsTrue() {
        groupService.save(new SmartHomeGroup("lights", "Lights", List.of()));

        assertTrue(groupService.remove("lights"));
        assertTrue(groupService.find("lights").isEmpty());
    }

    @Test
    void removeReturnsFalseForUnknownGroup() {
        assertFalse(groupService.remove("missing"));
    }

    @Test
    void addDeviceAppendsToExistingGroup() {
        groupService.save(new SmartHomeGroup("lights", "Lights", List.of("kitchen_light")));

        Optional<SmartHomeGroup> updated = groupService.addDevice("lights", "desk_lamp");

        assertTrue(updated.isPresent());
        assertEquals(List.of("kitchen_light", "desk_lamp"), updated.get().deviceIds());
    }

    @Test
    void addDeviceIsIdempotentForExistingMember() {
        groupService.save(new SmartHomeGroup("lights", "Lights", List.of("kitchen_light")));

        Optional<SmartHomeGroup> updated = groupService.addDevice("lights", "kitchen_light");

        assertEquals(List.of("kitchen_light"), updated.get().deviceIds());
    }

    @Test
    void addDeviceReturnsEmptyForUnknownGroup() {
        assertTrue(groupService.addDevice("missing", "kitchen_light").isEmpty());
    }

    @Test
    void removeDeviceDropsMemberFromGroup() {
        groupService.save(new SmartHomeGroup("lights", "Lights", List.of("kitchen_light", "desk_lamp")));

        Optional<SmartHomeGroup> updated = groupService.removeDevice("lights", "kitchen_light");

        assertTrue(updated.isPresent());
        assertEquals(List.of("desk_lamp"), updated.get().deviceIds());
    }

    @Test
    void removeDeviceReturnsEmptyForUnknownGroup() {
        assertTrue(groupService.removeDevice("missing", "kitchen_light").isEmpty());
    }
}
