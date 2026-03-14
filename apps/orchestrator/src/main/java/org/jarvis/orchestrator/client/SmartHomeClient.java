package org.jarvis.orchestrator.client;

import org.jarvis.orchestrator.config.ServiceAuthFeignConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.List;
import java.util.Map;

@FeignClient(
        name = "smart-home-service",
        url = "${jarvis.smart-home.url:http://localhost:8086}",
        configuration = ServiceAuthFeignConfig.class)
public interface SmartHomeClient {

    @PostMapping("/api/v1/smarthome/devices/{deviceId}/action")
    ActionResult executeAction(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable("deviceId") String deviceId,
            @RequestBody ActionRequest request);

    record ActionRequest(String action, String payload) {
    }

    record ActionResult(
            boolean success,
            String userId,
            String action,
            String message,
            DeviceView device,
            String timestamp) {
    }

    record DeviceView(
            String id,
            String displayName,
            String room,
            String type,
            List<String> supportedActions,
            Map<String, Object> state,
            String provider,
            String updatedAt) {
    }
}
