package org.jarvis.smarthome.service.impl;

import org.jarvis.smarthome.config.MqttGateway;
import org.jarvis.smarthome.model.SmartHomeActionRequest;
import org.jarvis.smarthome.model.SmartHomeDeviceDefinition;
import org.jarvis.smarthome.model.SmartHomeDeviceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MqttSmartHomeTransportTest {

    @Mock
    private MqttGateway mqttGateway;

    @Test
    void dispatchSendsTopicAndPayloadToGateway() {
        MqttSmartHomeTransport transport = new MqttSmartHomeTransport(mqttGateway);
        SmartHomeDeviceDefinition device = new SmartHomeDeviceDefinition(
                "kitchen_light", "Kitchen Light", "Kitchen", SmartHomeDeviceType.LIGHT,
                List.of("TOGGLE"), new LinkedHashMap<>(Map.of("power", false)));

        transport.dispatch("user-1", device, new SmartHomeActionRequest("TOGGLE", "on"));

        verify(mqttGateway).sendToMqtt("on", "jarvis/smarthome/user-1/kitchen_light/TOGGLE");
    }

    @Test
    void dispatchSendsEmptyStringWhenPayloadIsNull() {
        MqttSmartHomeTransport transport = new MqttSmartHomeTransport(mqttGateway);
        SmartHomeDeviceDefinition device = new SmartHomeDeviceDefinition(
                "front_door_lock", "Front Door", "Entrance", SmartHomeDeviceType.LOCK,
                List.of("LOCK"), new LinkedHashMap<>(Map.of("locked", true)));

        transport.dispatch("user-2", device, new SmartHomeActionRequest("LOCK", null));

        verify(mqttGateway).sendToMqtt("", "jarvis/smarthome/user-2/front_door_lock/LOCK");
    }

    @Test
    void providerNameIsMqtt() {
        MqttSmartHomeTransport transport = new MqttSmartHomeTransport(mqttGateway);

        assertEquals("mqtt", transport.providerName());
    }
}
