package org.jarvis.smarthome.service;

import org.jarvis.smarthome.model.SmartHomeAutomationRule;
import org.jarvis.smarthome.model.SmartHomeTriggerEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmartHomeAutomationRuleRegistryTest {

    private SmartHomeAutomationRuleRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SmartHomeAutomationRuleRegistry();
    }

    @Test
    void saveStoresRuleAndAllReturnsIt() {
        SmartHomeAutomationRule rule = rule("motion-light", true);

        SmartHomeAutomationRule saved = registry.save(rule);

        assertEquals(rule, saved);
        assertEquals(1, registry.all().size());
    }

    @Test
    void saveReplacesExistingRuleWithSameId() {
        registry.save(rule("motion-light", true));
        SmartHomeAutomationRule replacement = rule("motion-light", false);

        registry.save(replacement);

        assertEquals(1, registry.all().size());
        assertEquals(replacement, registry.find("motion-light").get());
    }

    @Test
    void findReturnsEmptyForUnknownRule() {
        assertTrue(registry.find("missing").isEmpty());
    }

    @Test
    void removeDeletesRuleAndReturnsTrue() {
        registry.save(rule("motion-light", true));

        assertTrue(registry.remove("motion-light"));
        assertTrue(registry.find("motion-light").isEmpty());
    }

    @Test
    void removeReturnsFalseForUnknownRule() {
        assertFalse(registry.remove("missing"));
    }

    private static SmartHomeAutomationRule rule(String id, boolean enabled) {
        return new SmartHomeAutomationRule(
                id, "Motion turns on kitchen light", "hall_motion",
                SmartHomeTriggerEvent.MOTION_DETECTED, null, "kitchen_light", "TURN_ON", null,
                false, enabled);
    }
}
