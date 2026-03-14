package org.jarvis.smarthome.service;

import org.jarvis.smarthome.model.SmartHomeActionRequest;
import org.jarvis.smarthome.model.SmartHomeDeviceDefinition;

public interface SmartHomeCommandTransport {

    void dispatch(String userId, SmartHomeDeviceDefinition device, SmartHomeActionRequest request);

    String providerName();
}
