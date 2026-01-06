package org.jarvis.smarthome.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.smarthome.config.MqttGateway;
import org.jarvis.smarthome.service.SmartHomeService;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class MqttSmartHomeService implements SmartHomeService {

    private final MqttGateway mqttGateway;

    @Override
    public void sendAction(String deviceId, String action, String payload) {
        String topic = "jarvis/smarthome/" + deviceId + "/" + action;
        log.info("Sending MQTT message to topic: {}, payload: {}", topic, payload);
        mqttGateway.sendToMqtt(payload, topic);
    }
}
