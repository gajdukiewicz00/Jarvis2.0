package org.jarvis.smarthome.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.smarthome.model.SmartHomeActionRequest;
import org.jarvis.smarthome.model.SmartHomeDeviceDefinition;
import org.jarvis.smarthome.service.SmartHomeCommandTransport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@ConditionalOnProperty(name = "smarthome.provider", havingValue = "mock")
public class MockSmartHomeTransport implements SmartHomeCommandTransport {

    @Override
    public void dispatch(String userId, SmartHomeDeviceDefinition device, SmartHomeActionRequest request) {
        log.info("Mock smart-home dispatch for user={} device={} action={} payload={}",
                userId, device.id(), request.action(), request.payload());
    }

    @Override
    public String providerName() {
        return "mock";
    }
}
