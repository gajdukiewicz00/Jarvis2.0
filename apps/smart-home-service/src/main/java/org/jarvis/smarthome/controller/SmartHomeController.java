package org.jarvis.smarthome.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.smarthome.history.DeviceStateHistoryEntry;
import org.jarvis.smarthome.history.DeviceStateHistoryService;
import org.jarvis.smarthome.model.SmartHomeActionRequest;
import org.jarvis.smarthome.model.SmartHomeActionResult;
import org.jarvis.smarthome.model.SmartHomeAutomationRule;
import org.jarvis.smarthome.model.SmartHomeAutomationSimulation;
import org.jarvis.smarthome.model.SmartHomeDeviceView;
import org.jarvis.smarthome.model.SmartHomeDeviceDefinition;
import org.jarvis.smarthome.model.SmartHomeDeviceType;
import org.jarvis.smarthome.model.SmartHomeDiscoveryResult;
import org.jarvis.smarthome.model.SmartHomeGroup;
import org.jarvis.smarthome.model.SmartHomeIntentResolution;
import org.jarvis.smarthome.model.SmartHomeRoom;
import org.jarvis.smarthome.model.SmartHomeScene;
import org.jarvis.smarthome.model.SmartHomeSceneActivation;
import org.jarvis.smarthome.model.SmartHomeSceneSimulation;
import org.jarvis.smarthome.model.SmartHomeSensorReading;
import org.jarvis.smarthome.service.SmartHomeAutomationEngine;
import org.jarvis.smarthome.service.SmartHomeAutomationRuleRegistry;
import org.jarvis.smarthome.service.SmartHomeDeviceCatalog;
import org.jarvis.smarthome.service.SmartHomeDeviceDiscoveryService;
import org.jarvis.smarthome.service.SmartHomeGroupService;
import org.jarvis.smarthome.service.SmartHomeIntentService;
import org.jarvis.smarthome.service.SmartHomeRoomService;
import org.jarvis.smarthome.service.SmartHomeSceneHistoryService;
import org.jarvis.smarthome.service.SmartHomeSceneService;
import org.jarvis.smarthome.service.SmartHomeSceneSimulationService;
import org.jarvis.smarthome.service.SmartHomeSensorService;
import org.jarvis.smarthome.service.SmartHomeService;
import org.jarvis.smarthome.service.SmartHomeDeviceNotFoundException;
import org.jarvis.smarthome.service.SmartHomeValidationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
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
    private final SmartHomeSceneHistoryService sceneHistoryService;
    private final SmartHomeGroupService groupService;
    private final SmartHomeRoomService roomService;
    private final SmartHomeSensorService sensorService;
    private final SmartHomeAutomationRuleRegistry automationRuleRegistry;
    private final SmartHomeAutomationEngine automationEngine;
    private final SmartHomeSceneSimulationService sceneSimulationService;
    private final DeviceStateHistoryService stateHistoryService;
    private final SmartHomeDeviceDiscoveryService discoveryService;
    private final SmartHomeIntentService intentService;
    private final Clock clock;

    /** Request body for runtime device registration. */
    public record DeviceRegistration(String id, String name, String room,
                                     SmartHomeDeviceType type, List<String> supportedActions,
                                     Map<String, Object> state) {
    }

    /** Request body for natural-language intent parsing, e.g. {@code "turn on the kitchen light"}. */
    public record IntentQuery(String utterance) {
    }

    /** Request body for creating/renaming a room. */
    public record RoomRequest(String id, String name) {
    }

    /** Request body for ingesting a sensor reading. */
    public record SensorReadingRequest(String metric, double value, String unit) {
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
            @RequestBody SmartHomeActionRequest request,
            @RequestParam(defaultValue = "false") boolean confirm) {

        log.info("Executing action for user={} device {}: action={}, payload={}, confirm={}",
                userId, deviceId, request.action(), request.payload(), confirm);

        try {
            String uid = requireUserId(userId);
            SmartHomeActionResult result = smartHomeService.executeAction(uid, deviceId, request, confirm);
            if (result.success()) {
                recordStateHistory(uid, deviceId, result, request.payload());
            }
            return ResponseEntity.ok(result);
        } catch (SmartHomeDeviceNotFoundException e) {
            return ResponseEntity.status(404).body(error("DEVICE_NOT_FOUND", e.getMessage()));
        } catch (SmartHomeValidationException e) {
            return ResponseEntity.badRequest().body(error("INVALID_ACTION", e.getMessage()));
        }
    }

    /** Best-effort audit trail write; a persistence hiccup must not fail an already-applied action. */
    private void recordStateHistory(String userId, String deviceId, SmartHomeActionResult result, String payload) {
        try {
            Map<String, Object> state = result.device() == null ? Map.of() : result.device().state();
            stateHistoryService.record(userId, deviceId, result.action(), payload, state, true);
        } catch (RuntimeException e) {
            log.warn("Failed to persist state history for device {}: {}", deviceId, e.getMessage());
        }
    }

    /**
     * Dry-run: evaluate automation rules against the current (or supplied) sensor
     * reading for a device — no devices are actuated. Mirrors
     * {@link #ingestSensorReading}'s request shape; when no body is supplied, the
     * latest known reading(s) for the device are used instead.
     */
    @PostMapping("/devices/{deviceId}/automation/simulate")
    public ResponseEntity<List<SmartHomeAutomationSimulation>> simulateAutomation(
            @PathVariable String deviceId,
            @RequestBody(required = false) SensorReadingRequest body) {
        List<SmartHomeSensorReading> readings = body != null && body.metric() != null && !body.metric().isBlank()
                ? List.of(new SmartHomeSensorReading(deviceId, body.metric(), body.value(), body.unit(), clock.instant()))
                : sensorService.latestForDevice(deviceId);

        List<SmartHomeAutomationSimulation> results = new ArrayList<>();
        for (SmartHomeSensorReading reading : readings) {
            results.addAll(automationEngine.simulate(reading));
        }
        return ResponseEntity.ok(results);
    }

    /** Dry-run: the actions a scene activation would take, without executing any of them. */
    @PostMapping("/scenes/{name}/simulate")
    public ResponseEntity<SmartHomeSceneSimulation> simulateScene(
            @PathVariable String name,
            @RequestParam(defaultValue = "false") boolean confirm) {
        return ResponseEntity.ok(sceneSimulationService.simulate(name, confirm));
    }

    /** Bounded, most-recent-first page of persisted state changes for a device. */
    @GetMapping("/devices/{deviceId}/state-history")
    public List<DeviceStateHistoryEntry> deviceStateHistory(
            @PathVariable String deviceId,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        return stateHistoryService.history(deviceId, limit);
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

    /**
     * Parse a natural-language command (EN or RU) and resolve it against the device registry.
     *
     * <p>This only plans the command — it never actuates hardware. To act on the plan, call
     * {@link #executeAction} with the resolved {@code deviceId} and {@code action}.
     */
    @PostMapping("/intent")
    public ResponseEntity<SmartHomeIntentResolution> resolveIntent(@RequestBody IntentQuery body) {
        return ResponseEntity.ok(intentService.resolve(body == null ? null : body.utterance()));
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
            recordSceneActivation(name, uid, results);
            return ResponseEntity.ok(Map.of("scene", name, "applied", results.size(), "results", results));
        } catch (SmartHomeValidationException e) {
            return ResponseEntity.badRequest().body(error("MISSING_USER_CONTEXT", e.getMessage()));
        }
    }

    /** Scene activation history, most-recent first. */
    @GetMapping("/scenes/history")
    public List<SmartHomeSceneActivation> sceneHistory(
            @RequestParam(required = false, defaultValue = "50") int limit) {
        return sceneHistoryService.recent(limit);
    }

    private void recordSceneActivation(String name, String uid, List<Object> results) {
        long successCount = results.stream()
                .filter(r -> r instanceof SmartHomeActionResult sar && sar.success())
                .count();
        int total = results.size();
        sceneHistoryService.record(new SmartHomeSceneActivation(
                name, uid, clock.instant(), total, (int) successCount, total - (int) successCount));
    }

    /** List all device groups. */
    @GetMapping("/groups")
    public List<SmartHomeGroup> groups() {
        return groupService.all();
    }

    /** Create or replace a device group. */
    @PostMapping("/groups")
    public ResponseEntity<?> createGroup(@RequestBody SmartHomeGroup group) {
        if (group == null || group.id() == null || group.id().isBlank()) {
            return ResponseEntity.badRequest().body(error("INVALID_GROUP", "group id is required"));
        }
        return ResponseEntity.ok(groupService.save(group));
    }

    /** Remove a group by id. */
    @DeleteMapping("/groups/{groupId}")
    public ResponseEntity<?> deleteGroup(@PathVariable String groupId) {
        return groupService.remove(groupId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(404).body(error("GROUP_NOT_FOUND", groupId));
    }

    /** Add a device to an existing group. */
    @PostMapping("/groups/{groupId}/devices/{deviceId}")
    public ResponseEntity<?> addDeviceToGroup(@PathVariable String groupId, @PathVariable String deviceId) {
        return groupService.addDevice(groupId, deviceId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(error("GROUP_NOT_FOUND", groupId)));
    }

    /** Remove a device from an existing group. */
    @DeleteMapping("/groups/{groupId}/devices/{deviceId}")
    public ResponseEntity<?> removeDeviceFromGroup(@PathVariable String groupId, @PathVariable String deviceId) {
        return groupService.removeDevice(groupId, deviceId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(error("GROUP_NOT_FOUND", groupId)));
    }

    /** Apply the same action to every device in a group. */
    @PostMapping("/groups/{groupId}/action")
    public ResponseEntity<?> actOnGroup(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable String groupId,
            @RequestBody SmartHomeActionRequest request) {
        try {
            String uid = requireUserId(userId);
            SmartHomeGroup group = groupService.find(groupId).orElse(null);
            if (group == null) {
                return ResponseEntity.status(404).body(error("GROUP_NOT_FOUND", groupId));
            }
            List<Object> results = applyActionToDevices(uid, group.deviceIds(), request);
            return ResponseEntity.ok(Map.of("group", groupId, "applied", results.size(), "results", results));
        } catch (SmartHomeValidationException e) {
            return ResponseEntity.badRequest().body(error("MISSING_USER_CONTEXT", e.getMessage()));
        }
    }

    /** List all rooms. */
    @GetMapping("/rooms")
    public List<SmartHomeRoom> rooms() {
        return roomService.all();
    }

    /** Create a room, or rename an existing one (its device assignments are preserved). */
    @PostMapping("/rooms")
    public ResponseEntity<?> createRoom(@RequestBody RoomRequest body) {
        if (body == null || body.id() == null || body.id().isBlank()) {
            return ResponseEntity.badRequest().body(error("INVALID_ROOM", "room id is required"));
        }
        String name = body.name() == null || body.name().isBlank() ? body.id() : body.name();
        return ResponseEntity.ok(roomService.createOrUpdate(body.id(), name));
    }

    /** Remove a room by id. */
    @DeleteMapping("/rooms/{roomId}")
    public ResponseEntity<?> deleteRoom(@PathVariable String roomId) {
        return roomService.remove(roomId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(404).body(error("ROOM_NOT_FOUND", roomId));
    }

    /** Assign a device to a room (removing it from any other room first). */
    @PostMapping("/rooms/{roomId}/devices/{deviceId}")
    public ResponseEntity<?> assignDeviceToRoom(@PathVariable String roomId, @PathVariable String deviceId) {
        return roomService.assignDevice(roomId, deviceId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(error("ROOM_NOT_FOUND", roomId)));
    }

    /** Unassign a device from a room. */
    @DeleteMapping("/rooms/{roomId}/devices/{deviceId}")
    public ResponseEntity<?> unassignDeviceFromRoom(@PathVariable String roomId, @PathVariable String deviceId) {
        return roomService.unassignDevice(roomId, deviceId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(error("ROOM_NOT_FOUND", roomId)));
    }

    /** Apply the same action to every device assigned to a room. */
    @PostMapping("/rooms/{roomId}/action")
    public ResponseEntity<?> actOnRoom(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @PathVariable String roomId,
            @RequestBody SmartHomeActionRequest request) {
        try {
            String uid = requireUserId(userId);
            SmartHomeRoom room = roomService.find(roomId).orElse(null);
            if (room == null) {
                return ResponseEntity.status(404).body(error("ROOM_NOT_FOUND", roomId));
            }
            List<Object> results = applyActionToDevices(uid, room.deviceIds(), request);
            return ResponseEntity.ok(Map.of("room", roomId, "applied", results.size(), "results", results));
        } catch (SmartHomeValidationException e) {
            return ResponseEntity.badRequest().body(error("MISSING_USER_CONTEXT", e.getMessage()));
        }
    }

    private List<Object> applyActionToDevices(String userId, List<String> deviceIds, SmartHomeActionRequest request) {
        List<Object> results = new ArrayList<>();
        for (String deviceId : deviceIds) {
            try {
                results.add(smartHomeService.executeAction(userId, deviceId, request));
            } catch (RuntimeException e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                results.add(Map.of("deviceId", deviceId, "error", msg));
            }
        }
        return results;
    }

    /** Ingest a sensor reading for a device; also evaluates automation rules. */
    @PostMapping("/devices/{deviceId}/sensor-readings")
    public ResponseEntity<?> ingestSensorReading(
            @PathVariable String deviceId,
            @RequestBody SensorReadingRequest body) {
        try {
            SmartHomeSensorReading reading = sensorService.ingest(
                    deviceId,
                    body == null ? null : body.metric(),
                    body == null ? 0 : body.value(),
                    body == null ? null : body.unit());
            return ResponseEntity.ok(reading);
        } catch (SmartHomeDeviceNotFoundException e) {
            return ResponseEntity.status(404).body(error("DEVICE_NOT_FOUND", e.getMessage()));
        } catch (SmartHomeValidationException e) {
            return ResponseEntity.badRequest().body(error("INVALID_READING", e.getMessage()));
        }
    }

    /** Latest reading per metric for a device, or a single metric if {@code metric} is given. */
    @GetMapping("/devices/{deviceId}/sensor-readings/latest")
    public ResponseEntity<?> latestSensorReadings(
            @PathVariable String deviceId,
            @RequestParam(required = false) String metric) {
        if (metric != null && !metric.isBlank()) {
            return sensorService.latest(deviceId, metric)
                    .<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.status(404).body(error("READING_NOT_FOUND", metric)));
        }
        return ResponseEntity.ok(sensorService.latestForDevice(deviceId));
    }

    /** Recent reading history for a device/metric pair. */
    @GetMapping("/devices/{deviceId}/sensor-readings")
    public List<SmartHomeSensorReading> sensorReadingHistory(
            @PathVariable String deviceId,
            @RequestParam String metric) {
        return sensorService.history(deviceId, metric);
    }

    /** List all automation rules. */
    @GetMapping("/automation/rules")
    public List<SmartHomeAutomationRule> automationRules() {
        return automationRuleRegistry.all();
    }

    /** Create or replace an automation rule (trigger → action). */
    @PostMapping("/automation/rules")
    public ResponseEntity<?> createAutomationRule(@RequestBody SmartHomeAutomationRule rule) {
        if (rule == null || isBlank(rule.id()) || isBlank(rule.triggerDeviceId()) || rule.triggerEvent() == null
                || isBlank(rule.actionDeviceId()) || isBlank(rule.actionType())) {
            return ResponseEntity.badRequest().body(error("INVALID_RULE",
                    "id, triggerDeviceId, triggerEvent, actionDeviceId and actionType are required"));
        }
        return ResponseEntity.ok(automationRuleRegistry.save(rule));
    }

    /** Remove an automation rule by id. */
    @DeleteMapping("/automation/rules/{ruleId}")
    public ResponseEntity<?> deleteAutomationRule(@PathVariable String ruleId) {
        return automationRuleRegistry.remove(ruleId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(404).body(error("RULE_NOT_FOUND", ruleId));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Dynamic device-discovery stub — a real implementation would scan MQTT announcement topics. */
    @PostMapping("/discovery/scan")
    public SmartHomeDiscoveryResult scanForDevices() {
        return discoveryService.scan();
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
