package org.jarvis.smarthome.service;

import lombok.RequiredArgsConstructor;
import org.jarvis.smarthome.model.SmartHomeSensorReading;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores recent sensor readings in memory (bounded per device/metric) and
 * forwards every ingested reading to the automation engine for rule
 * evaluation. Readings are keyed by {@code deviceId::METRIC} (metric
 * normalized to upper case) so lookups are case-insensitive.
 */
@Service
@RequiredArgsConstructor
public class SmartHomeSensorService {

    private static final int MAX_HISTORY_PER_KEY = 50;

    private final SmartHomeDeviceCatalog catalog;
    private final SmartHomeAutomationEngine automationEngine;
    private final Clock clock;

    private final Map<String, Deque<SmartHomeSensorReading>> historyByKey = new ConcurrentHashMap<>();

    /** Ingest a reading for a known device, store it, and evaluate automation rules against it. */
    public SmartHomeSensorReading ingest(String deviceId, String metric, double value, String unit) {
        catalog.findById(deviceId).orElseThrow(() -> new SmartHomeDeviceNotFoundException(deviceId));
        if (metric == null || metric.isBlank()) {
            throw new SmartHomeValidationException("metric is required");
        }

        SmartHomeSensorReading reading = new SmartHomeSensorReading(
                deviceId, normalizeMetric(metric), value, unit, clock.instant());

        Deque<SmartHomeSensorReading> readings =
                historyByKey.computeIfAbsent(key(deviceId, reading.metric()), ignored -> new ArrayDeque<>());
        synchronized (readings) {
            readings.addFirst(reading);
            while (readings.size() > MAX_HISTORY_PER_KEY) {
                readings.removeLast();
            }
        }

        automationEngine.evaluate(reading);
        return reading;
    }

    /** The most recent reading for a specific metric on a device, if any. */
    public Optional<SmartHomeSensorReading> latest(String deviceId, String metric) {
        Deque<SmartHomeSensorReading> readings = historyByKey.get(key(deviceId, normalizeMetric(metric)));
        if (readings == null) {
            return Optional.empty();
        }
        synchronized (readings) {
            return Optional.ofNullable(readings.peekFirst());
        }
    }

    /** The most recent reading for every metric reported by a device. */
    public List<SmartHomeSensorReading> latestForDevice(String deviceId) {
        String prefix = deviceId + "::";
        return historyByKey.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .map(entry -> {
                    synchronized (entry.getValue()) {
                        return entry.getValue().peekFirst();
                    }
                })
                .filter(Objects::nonNull)
                .toList();
    }

    /** Recent history (most-recent first) for a device/metric pair. Empty if nothing has been ingested. */
    public List<SmartHomeSensorReading> history(String deviceId, String metric) {
        Deque<SmartHomeSensorReading> readings = historyByKey.get(key(deviceId, normalizeMetric(metric)));
        if (readings == null) {
            return List.of();
        }
        synchronized (readings) {
            return List.copyOf(readings);
        }
    }

    private static String normalizeMetric(String metric) {
        if (metric == null) {
            throw new SmartHomeValidationException("metric is required");
        }
        return metric.trim().toUpperCase(Locale.ROOT);
    }

    private static String key(String deviceId, String normalizedMetric) {
        return deviceId + "::" + normalizedMetric;
    }
}
