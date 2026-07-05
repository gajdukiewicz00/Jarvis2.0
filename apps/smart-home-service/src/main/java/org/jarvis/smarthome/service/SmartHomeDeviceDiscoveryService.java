package org.jarvis.smarthome.service;

import org.jarvis.smarthome.model.SmartHomeDiscoveryResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Placeholder for dynamic device discovery over MQTT.
 *
 * <p>A real implementation would subscribe to a wildcard announcement topic
 * (e.g. {@code jarvis/smarthome/+/announce}) for a bounded window, parse
 * device-announcement payloads, and register any newly-seen devices with
 * {@link SmartHomeDeviceCatalog}. That requires a reachable, live MQTT broker
 * and real field devices, so it is out of scope for a headless build/test
 * environment — this stub reports what it *would* do without opening a
 * connection, keeping the endpoint safe to call in any environment.
 */
@Service
public class SmartHomeDeviceDiscoveryService {

    @Value("${jarvis.mqtt.broker-url:${mqtt.broker-url:tcp://localhost:1883}}")
    private String brokerUrl;

    public SmartHomeDiscoveryResult scan() {
        return new SmartHomeDiscoveryResult(
                false,
                brokerUrl,
                List.of(),
                "Discovery stub: no live MQTT topic scan was performed. Wire an MQTT subscriber to "
                        + "jarvis/smarthome/+/announce against a reachable broker to populate this list "
                        + "from real device announcements.");
    }
}
