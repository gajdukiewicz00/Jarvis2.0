package org.jarvis.smarthome.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.smarthome.config.MqttGateway;
import org.jarvis.smarthome.model.SmartHomeActionRequest;
import org.jarvis.smarthome.model.SmartHomeDeviceDefinition;
import org.jarvis.smarthome.service.SmartHomeCommandTransport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "smarthome.provider", havingValue = "mqtt", matchIfMissing = true)
public class MqttSmartHomeTransport implements SmartHomeCommandTransport {

    private final MqttGateway mqttGateway;

    @Override
    public void dispatch(String userId, SmartHomeDeviceDefinition device, SmartHomeActionRequest request) {
        String topic = "jarvis/smarthome/" + userId + "/" + device.id() + "/" + request.action();
        String payload = request.payload() == null ? "" : request.payload();
        log.info("Sending MQTT message to topic: {}, payload: {}", topic, payload);
        mqttGateway.sendToMqtt(payload, topic);
    }

    @Override
    public String providerName() {
        return "mqtt";
    }
}
