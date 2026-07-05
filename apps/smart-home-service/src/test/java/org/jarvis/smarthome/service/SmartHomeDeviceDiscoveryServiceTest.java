package org.jarvis.smarthome.service;

import org.jarvis.smarthome.model.SmartHomeDiscoveryResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmartHomeDeviceDiscoveryServiceTest {

    @Test
    void scanReturnsUnsupportedStubResultWithoutOpeningAConnection() {
        SmartHomeDeviceDiscoveryService discoveryService = new SmartHomeDeviceDiscoveryService();
        ReflectionTestUtils.setField(discoveryService, "brokerUrl", "tcp://mosquitto:1883");

        SmartHomeDiscoveryResult result = discoveryService.scan();

        assertFalse(result.supported());
        assertEquals("tcp://mosquitto:1883", result.brokerUrl());
        assertTrue(result.discovered().isEmpty());
        assertNotNull(result.note());
        assertTrue(result.note().toLowerCase().contains("stub"));
    }
}
