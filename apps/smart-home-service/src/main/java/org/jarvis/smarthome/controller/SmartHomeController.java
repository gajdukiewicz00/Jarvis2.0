package org.jarvis.smarthome.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.smarthome.service.SmartHomeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
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

    /**
     * Execute action on a smart home device.
     * 
     * @param deviceId Device identifier
     * @param request Action request with action type and payload
     * @return Structured response with action details
     */
    @PostMapping("/devices/{deviceId}/action")
    public ResponseEntity<Map<String, Object>> executeAction(
            @PathVariable String deviceId, 
            @RequestBody ActionRequest request) {
        
        log.info("Executing action for device {}: action={}, payload={}", 
                deviceId, request.action(), request.payload());
        
        // Validate action
        if (request.action() == null || request.action().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "error", "INVALID_ACTION",
                "message", "Action is required"
            ));
        }
        
        try {
            smartHomeService.sendAction(deviceId, request.action(), request.payload());
            
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("deviceId", deviceId);
            response.put("action", request.action());
            response.put("payload", request.payload());
            response.put("timestamp", LocalDateTime.now().toString());
            response.put("message", "Action sent successfully via MQTT");
            
            return ResponseEntity.ok(response);
            
        } catch (RuntimeException e) {
            log.error("Failed to execute action for device {}: {}", deviceId, e.getMessage());
            
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("success", false);
            error.put("deviceId", deviceId);
            error.put("action", request.action());
            error.put("error", "ACTION_FAILED");
            error.put("message", "Failed to send action: " + e.getMessage());
            error.put("timestamp", LocalDateTime.now().toString());
            
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * Get list of supported actions.
     */
    @GetMapping("/actions")
    public ResponseEntity<Map<String, Object>> getSupportedActions() {
        return ResponseEntity.ok(Map.of(
            "supportedActions", java.util.List.of(
                "TURN_ON", "TURN_OFF", "DIM", "BRIGHTEN", 
                "SET_COLOR", "SET_TEMPERATURE", "LOCK", "UNLOCK"
            ),
            "description", "Smart home device control via MQTT"
        ));
    }

    public record ActionRequest(String action, String payload) {}
}
