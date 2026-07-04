package org.jarvis.smarthome.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.smarthome.model.SmartHomeActionRequest;
import org.jarvis.smarthome.model.SmartHomeActionResult;
import org.jarvis.smarthome.model.SmartHomeDeviceView;
import org.jarvis.smarthome.model.SmartHomeDeviceDefinition;
import org.jarvis.smarthome.model.SmartHomeDeviceType;
import org.jarvis.smarthome.model.SmartHomeScene;
import org.jarvis.smarthome.service.SmartHomeDeviceCatalog;
import org.jarvis.smarthome.service.SmartHomeSceneService;
import org.jarvis.smarthome.service.SmartHomeService;
import org.jarvis.smarthome.service.SmartHomeDeviceNotFoundException;
import org.jarvis.smarthome.service.SmartHomeValidationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
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
    private final SmartHomeDeviceCatalog deviceCatalog;
    private final SmartHomeSceneService sceneService;

    /** Request body for runtime device registration. */
    public record DeviceRegistration(String id, String name, String room,
                                     SmartHomeDeviceType type, List<String> supportedActions,
                                     Map<String, Object> state) {
    }

    /** Full device catalog (definitions), reflecting any runtime additions. */
    @GetMapping("/catalog")
    public List<SmartHomeDeviceDefinition> catalog() {
        return deviceCatalog.all();
    }

    /** Add (or replace) a device at runtime — no redeploy needed. */
    @PostMapping("/devices")
    public ResponseEntity<?> registerDevice(@RequestBody DeviceRegistration body) {
        if (body.id() == null || body.id().isBlank() || body.type() == null) {
            return ResponseEntity.badRequest().body(error("INVALID_DEVICE", "id and type are required"));
        }
        SmartHomeDeviceDefinition def = new SmartHomeDeviceDefinition(
                body.id(),
                body.name() == null ? body.id() : body.name(),
                body.room() == null ? "Home" : body.room(),
                body.type(),
                body.supportedActions() == null ? List.of() : body.supportedActions(),
                new java.util.LinkedHashMap<>(body.state() == null ? Map.of() : body.state()));
        return ResponseEntity.ok(deviceCatalog.register(def));
    }

    /** Remove a device by id. */
    @DeleteMapping("/devices/{deviceId}")
    public ResponseEntity<?> removeDevice(@PathVariable String deviceId) {
        return deviceCatalog.remove(deviceId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(404).body(error("DEVICE_NOT_FOUND", deviceId));
    }

    @GetMapping("/devices")
    public ResponseEntity<?> listDevices(
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        try {
            return ResponseEntity.ok(smartHomeService.listDevices(requireUserId(userId)));
        } catch (SmartHomeValidationException e) {
            return ResponseEntity.badRequest().body(error("MISSING_USER_CONTEXT", e.getMessage()));
        }
    }

    @GetMapping("/devices/{deviceId}")
    public ResponseEntity<?> getDevice(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable String deviceId) {
        try {
            return ResponseEntity.ok(smartHomeService.getDevice(requireUserId(userId), deviceId));
        } catch (SmartHomeDeviceNotFoundException e) {
            return ResponseEntity.status(404).body(error("DEVICE_NOT_FOUND", e.getMessage()));
        } catch (SmartHomeValidationException e) {
            return ResponseEntity.badRequest().body(error("MISSING_USER_CONTEXT", e.getMessage()));
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
            SmartHomeActionResult result = smartHomeService.executeAction(requireUserId(userId), deviceId, request);
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

    /** List all scenes. */
    @GetMapping("/scenes")
    public List<SmartHomeScene> scenes() {
        return sceneService.all();
    }

    /** Create or replace a scene (a named set of device actions). */
    @PostMapping("/scenes")
    public ResponseEntity<?> createScene(@RequestBody SmartHomeScene scene) {
        if (scene == null || scene.name() == null || scene.name().isBlank()) {
            return ResponseEntity.badRequest().body(error("INVALID_SCENE", "scene name is required"));
        }
        return ResponseEntity.ok(sceneService.save(scene));
    }

    /** Remove a scene by name. */
    @DeleteMapping("/scenes/{name}")
    public ResponseEntity<?> deleteScene(@PathVariable String name) {
        return sceneService.remove(name)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(404).body(error("SCENE_NOT_FOUND", name));
    }

    /** Activate a scene — apply every step via the device action pipeline. */
    @PostMapping("/scenes/{name}/activate")
    public ResponseEntity<?> activateScene(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable String name) {
        try {
            String uid = requireUserId(userId);
            SmartHomeScene scene = sceneService.find(name).orElse(null);
            if (scene == null) {
                return ResponseEntity.status(404).body(error("SCENE_NOT_FOUND", name));
            }
            List<Object> results = new ArrayList<>();
            List<SmartHomeScene.SceneStep> steps = scene.steps() == null ? List.of() : scene.steps();
            for (SmartHomeScene.SceneStep step : steps) {
                try {
                    results.add(smartHomeService.executeAction(uid, step.deviceId(),
                            new SmartHomeActionRequest(step.action(), step.payload())));
                } catch (RuntimeException e) {
                    // Map.of rejects nulls — guard both the device id and the message.
                    String dev = step.deviceId() != null ? step.deviceId() : "";
                    String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    results.add(Map.of("deviceId", dev, "error", msg));
                }
            }
            return ResponseEntity.ok(Map.of("scene", name, "applied", results.size(), "results", results));
        } catch (SmartHomeValidationException e) {
            return ResponseEntity.badRequest().body(error("MISSING_USER_CONTEXT", e.getMessage()));
        }
    }

    private Map<String, Object> error(String code, String message) {
        return Map.of(
                "success", false,
                "error", code,
                "message", message);
    }

    private String requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new SmartHomeValidationException("Delegated user context is required");
        }
        return userId.trim();
    }
}
