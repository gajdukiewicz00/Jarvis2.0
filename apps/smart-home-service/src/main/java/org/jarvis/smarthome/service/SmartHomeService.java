package org.jarvis.smarthome.service;

import org.jarvis.smarthome.model.SmartHomeActionRequest;
import org.jarvis.smarthome.model.SmartHomeActionResult;
import org.jarvis.smarthome.model.SmartHomeDeviceView;

import java.util.List;

public interface SmartHomeService {

    List<SmartHomeDeviceView> listDevices(String userId);

    SmartHomeDeviceView getDevice(String userId, String deviceId);

    /** Equivalent to {@code executeAction(userId, deviceId, request, false)} — not confirmed. */
    default SmartHomeActionResult executeAction(String userId, String deviceId, SmartHomeActionRequest request) {
        return executeAction(userId, deviceId, request, false);
    }

    /**
     * Execute a device action. {@code confirmed} must be {@code true} for
     * security-critical device types (see {@code org.jarvis.smarthome.security.SafetyPolicy}
     * — currently {@code LOCK}, {@code DOOR}, {@code GARAGE}) or the action is not
     * applied and the result carries {@code needsConfirmation() == true} instead.
     */
    SmartHomeActionResult executeAction(String userId, String deviceId, SmartHomeActionRequest request, boolean confirmed);

    List<String> supportedActions();
}
