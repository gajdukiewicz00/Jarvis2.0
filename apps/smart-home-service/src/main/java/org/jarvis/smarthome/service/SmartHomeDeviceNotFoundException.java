package org.jarvis.smarthome.service;

public class SmartHomeDeviceNotFoundException extends RuntimeException {

    public SmartHomeDeviceNotFoundException(String deviceId) {
        super("Unknown smart-home device: " + deviceId);
    }
}
