package org.jarvis.smarthome.service;

import org.jarvis.smarthome.model.SmartHomeSensorReading;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SmartHomeSensorServiceTest {

    @Mock
    private SmartHomeAutomationEngine automationEngine;

    private SmartHomeDeviceCatalog catalog;
    private SmartHomeSensorService sensorService;

    @BeforeEach
    void setUp() {
        catalog = new SmartHomeDeviceCatalog();
        sensorService = new SmartHomeSensorService(
                catalog, automationEngine, Clock.fixed(Instant.parse("2026-03-14T10:30:00Z"), ZoneOffset.UTC));
    }

    @Test
    void ingestRejectsUnknownDevice() {
        assertThrows(SmartHomeDeviceNotFoundException.class,
                () -> sensorService.ingest("missing_device", "TEMPERATURE", 21.5, "C"));
    }

    @Test
    void ingestRejectsBlankMetric() {
        assertThrows(SmartHomeValidationException.class,
                () -> sensorService.ingest("kitchen_light", " ", 1.0, null));
    }

    @Test
    void ingestStoresReadingAndLatestReturnsIt() {
        SmartHomeSensorReading reading = sensorService.ingest("hall_thermostat", "temperature", 21.5, "C");

        assertEquals("hall_thermostat", reading.deviceId());
        assertEquals("TEMPERATURE", reading.metric());
        assertEquals(Instant.parse("2026-03-14T10:30:00Z"), reading.recordedAt());

        Optional<SmartHomeSensorReading> latest = sensorService.latest("hall_thermostat", "temperature");
        assertTrue(latest.isPresent());
        assertEquals(21.5, latest.get().value());
    }

    @Test
    void ingestNormalizesMetricCaseForLookup() {
        sensorService.ingest("hall_thermostat", "Temperature", 20.0, "C");

        assertTrue(sensorService.latest("hall_thermostat", "TEMPERATURE").isPresent());
        assertTrue(sensorService.latest("hall_thermostat", "temperature").isPresent());
    }

    @Test
    void ingestDelegatesToAutomationEngineForEvaluation() {
        sensorService.ingest("hall_thermostat", "TEMPERATURE", 21.5, "C");

        verify(automationEngine).evaluate(any());
    }

    @Test
    void latestReturnsEmptyWhenNoReadingRecorded() {
        assertTrue(sensorService.latest("hall_thermostat", "TEMPERATURE").isEmpty());
    }

    @Test
    void latestForDeviceReturnsMostRecentPerMetric() {
        sensorService.ingest("hall_thermostat", "TEMPERATURE", 20.0, "C");
        sensorService.ingest("hall_thermostat", "TEMPERATURE", 22.0, "C");
        sensorService.ingest("hall_thermostat", "HUMIDITY", 45.0, "%");

        List<SmartHomeSensorReading> latest = sensorService.latestForDevice("hall_thermostat");

        assertEquals(2, latest.size());
        assertTrue(latest.stream().anyMatch(r -> r.metric().equals("TEMPERATURE") && r.value() == 22.0));
        assertTrue(latest.stream().anyMatch(r -> r.metric().equals("HUMIDITY") && r.value() == 45.0));
    }

    @Test
    void historyReturnsMostRecentFirst() {
        sensorService.ingest("hall_thermostat", "TEMPERATURE", 20.0, "C");
        sensorService.ingest("hall_thermostat", "TEMPERATURE", 21.0, "C");
        sensorService.ingest("hall_thermostat", "TEMPERATURE", 22.0, "C");

        List<SmartHomeSensorReading> history = sensorService.history("hall_thermostat", "temperature");

        assertEquals(3, history.size());
        assertEquals(22.0, history.get(0).value());
        assertEquals(20.0, history.get(2).value());
    }

    @Test
    void historyReturnsEmptyListWhenNothingIngested() {
        assertTrue(sensorService.history("hall_thermostat", "TEMPERATURE").isEmpty());
    }

    @Test
    void historyIsBoundedPerDeviceMetricPair() {
        for (int i = 0; i < 60; i++) {
            sensorService.ingest("hall_thermostat", "TEMPERATURE", i, "C");
        }

        List<SmartHomeSensorReading> history = sensorService.history("hall_thermostat", "TEMPERATURE");

        assertEquals(50, history.size());
        assertEquals(59.0, history.get(0).value());
    }
}
