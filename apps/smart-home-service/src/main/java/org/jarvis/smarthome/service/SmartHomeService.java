package org.jarvis.smarthome.service;

import org.jarvis.smarthome.model.SmartHomeActionRequest;
import org.jarvis.smarthome.model.SmartHomeActionResult;
import org.jarvis.smarthome.model.SmartHomeDeviceView;

import java.util.List;

public interface SmartHomeService {

    List<SmartHomeDeviceView> listDevices(String userId);

    SmartHomeDeviceView getDevice(String userId, String deviceId);

    SmartHomeActionResult executeAction(String userId, String deviceId, SmartHomeActionRequest request);

    List<String> supportedActions();
}
