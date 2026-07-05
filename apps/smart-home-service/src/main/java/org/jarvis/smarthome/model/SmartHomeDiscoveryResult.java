package org.jarvis.smarthome.model;

import java.util.List;

/**
 * Result of a device-discovery scan. {@code supported=false} indicates the
 * stub path was used (no live MQTT topic scan performed) — see
 * {@code SmartHomeDeviceDiscoveryService}.
 */
public record SmartHomeDiscoveryResult(
        boolean supported,
        String brokerUrl,
        List<SmartHomeDeviceDefinition> discovered,
        String note) {
}
