package org.jarvis.smarthome.service;

public interface SmartHomeService {
    void sendAction(String deviceId, String action, String payload);
}
