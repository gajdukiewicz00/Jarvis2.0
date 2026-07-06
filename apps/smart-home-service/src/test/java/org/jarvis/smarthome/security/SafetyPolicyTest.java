package org.jarvis.smarthome.security;

import org.jarvis.smarthome.model.SmartHomeDeviceType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafetyPolicyTest {

    private final SafetyPolicy policy = new SafetyPolicy();

    @Test
    void locksDoorsAndGaragesAreSecurityCritical() {
        assertTrue(policy.isSecurityCritical(SmartHomeDeviceType.LOCK));
        assertTrue(policy.isSecurityCritical(SmartHomeDeviceType.DOOR));
        assertTrue(policy.isSecurityCritical(SmartHomeDeviceType.GARAGE));
    }

    @Test
    void lightsThermostatsAndSwitchesAreNotSecurityCritical() {
        assertFalse(policy.isSecurityCritical(SmartHomeDeviceType.LIGHT));
        assertFalse(policy.isSecurityCritical(SmartHomeDeviceType.THERMOSTAT));
        assertFalse(policy.isSecurityCritical(SmartHomeDeviceType.SWITCH));
    }

    @Test
    void sensorTypesAreNotSecurityCritical() {
        assertFalse(policy.isSecurityCritical(SmartHomeDeviceType.DOOR_SENSOR));
        assertFalse(policy.isSecurityCritical(SmartHomeDeviceType.MOTION_SENSOR));
    }

    @Test
    void nullTypeIsNotSecurityCritical() {
        assertFalse(policy.isSecurityCritical(null));
    }

    @Test
    void requiresConfirmationOnlyForSecurityCriticalAndUnconfirmed() {
        assertTrue(policy.requiresConfirmation(SmartHomeDeviceType.LOCK, false));
        assertFalse(policy.requiresConfirmation(SmartHomeDeviceType.LOCK, true));
        assertTrue(policy.requiresConfirmation(SmartHomeDeviceType.DOOR, false));
        assertTrue(policy.requiresConfirmation(SmartHomeDeviceType.GARAGE, false));
        assertFalse(policy.requiresConfirmation(SmartHomeDeviceType.LIGHT, false));
        assertFalse(policy.requiresConfirmation(SmartHomeDeviceType.LIGHT, true));
    }

    @Test
    void requiresConfirmationIsFalseForNullType() {
        assertFalse(policy.requiresConfirmation(null, false));
    }
}
