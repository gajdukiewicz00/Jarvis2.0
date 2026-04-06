package org.jarvis.voicegateway.client;

public interface SmartHomeActionGateway {

    void execute(String userId, String deviceId, String action, Object payload);
}
