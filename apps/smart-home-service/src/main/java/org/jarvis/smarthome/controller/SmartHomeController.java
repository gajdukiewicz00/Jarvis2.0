package org.jarvis.smarthome.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.smarthome.model.SmartHomeActionRequest;
import org.jarvis.smarthome.model.SmartHomeActionResult;
import org.jarvis.smarthome.model.SmartHomeDeviceView;
import org.jarvis.smarthome.service.SmartHomeService;
import org.jarvis.smarthome.service.SmartHomeDeviceNotFoundException;
import org.jarvis.smarthome.service.SmartHomeValidationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Smart home device control controller.
 * 
 * Controls IoT devices via MQTT messaging.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/smarthome")
@RequiredArgsConstructor
public class SmartHomeController {

    private final SmartHomeService smartHomeService;

    @GetMapping("/devices")
    public ResponseEntity<List<SmartHomeDeviceView>> listDevices(
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return ResponseEntity.ok(smartHomeService.listDevices(userId));
    }

    @GetMapping("/devices/{deviceId}")
    public ResponseEntity<?> getDevice(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable String deviceId) {
        try {
            return ResponseEntity.ok(smartHomeService.getDevice(userId, deviceId));
        } catch (SmartHomeDeviceNotFoundException e) {
            return ResponseEntity.status(404).body(error("DEVICE_NOT_FOUND", e.getMessage()));
        }
    }

    @PostMapping("/devices/{deviceId}/action")
    public ResponseEntity<?> executeAction(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable String deviceId,
            @RequestBody SmartHomeActionRequest request) {

        log.info("Executing action for user={} device {}: action={}, payload={}",
                userId, deviceId, request.action(), request.payload());

        try {
            SmartHomeActionResult result = smartHomeService.executeAction(userId, deviceId, request);
            return ResponseEntity.ok(result);
        } catch (SmartHomeDeviceNotFoundException e) {
            return ResponseEntity.status(404).body(error("DEVICE_NOT_FOUND", e.getMessage()));
        } catch (SmartHomeValidationException e) {
            return ResponseEntity.badRequest().body(error("INVALID_ACTION", e.getMessage()));
        }
    }

    /**
     * Get list of supported actions.
     */
    @GetMapping("/actions")
    public ResponseEntity<Map<String, Object>> getSupportedActions() {
        return ResponseEntity.ok(Map.of(
            "supportedActions", smartHomeService.supportedActions(),
            "description", "Stateful smart-home control with local mock and MQTT transport support"
        ));
    }

    private Map<String, Object> error(String code, String message) {
        return Map.of(
                "success", false,
                "error", code,
                "message", message);
    }
}
