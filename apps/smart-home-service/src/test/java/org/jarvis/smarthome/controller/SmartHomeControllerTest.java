package org.jarvis.smarthome.controller;

import org.jarvis.smarthome.model.SmartHomeActionRequest;
import org.jarvis.smarthome.model.SmartHomeActionResult;
import org.jarvis.smarthome.model.SmartHomeAutomationRule;
import org.jarvis.smarthome.model.SmartHomeDeviceDefinition;
import org.jarvis.smarthome.model.SmartHomeDeviceType;
import org.jarvis.smarthome.model.SmartHomeDeviceView;
import org.jarvis.smarthome.model.SmartHomeDiscoveryResult;
import org.jarvis.smarthome.model.SmartHomeGroup;
import org.jarvis.smarthome.model.IntentMatchStatus;
import org.jarvis.smarthome.model.SmartHomeIntentResolution;
import org.jarvis.smarthome.model.SmartHomeRoom;
import org.jarvis.smarthome.model.SmartHomeScene;
import org.jarvis.smarthome.model.SmartHomeSceneActivation;
import org.jarvis.smarthome.model.SmartHomeSensorReading;
import org.jarvis.smarthome.model.SmartHomeTriggerEvent;
import org.jarvis.smarthome.service.SmartHomeAutomationRuleRegistry;
import org.jarvis.smarthome.service.SmartHomeDeviceCatalog;
import org.jarvis.smarthome.service.SmartHomeDeviceDiscoveryService;
import org.jarvis.smarthome.service.SmartHomeDeviceNotFoundException;
import org.jarvis.smarthome.service.SmartHomeGroupService;
import org.jarvis.smarthome.service.SmartHomeIntentService;
import org.jarvis.smarthome.service.SmartHomeRoomService;
import org.jarvis.smarthome.service.SmartHomeSceneHistoryService;
import org.jarvis.smarthome.service.SmartHomeSceneService;
import org.jarvis.smarthome.service.SmartHomeSensorService;
import org.jarvis.smarthome.service.SmartHomeService;
import org.jarvis.smarthome.service.SmartHomeValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmartHomeControllerTest {

    @Mock
    private SmartHomeService smartHomeService;

    @Mock
    private SmartHomeDeviceCatalog deviceCatalog;

    @Mock
    private SmartHomeSceneService sceneService;

    @Mock
    private SmartHomeSceneHistoryService sceneHistoryService;

    @Mock
    private SmartHomeGroupService groupService;

    @Mock
    private SmartHomeRoomService roomService;

    @Mock
    private SmartHomeSensorService sensorService;

    @Mock
    private SmartHomeAutomationRuleRegistry automationRuleRegistry;

    @Mock
    private SmartHomeDeviceDiscoveryService discoveryService;

    @Mock
    private SmartHomeIntentService intentService;

    @Mock
    private Clock clock;

    @InjectMocks
    private SmartHomeController controller;

    @Test
    void listDevicesReturnsUserScopedDevices() {
        when(smartHomeService.listDevices("user-1")).thenReturn(List.of(deviceView()));

        ResponseEntity<?> response = controller.listDevices("user-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        List<SmartHomeDeviceView> body = (List<SmartHomeDeviceView>) response.getBody();
        assertEquals(1, body.size());
        assertEquals("kitchen_light", body.getFirst().id());
    }

    @Test
    void executeActionReturnsUpdatedDeviceSnapshot() {
        when(smartHomeService.executeAction("user-1", "kitchen_light", new SmartHomeActionRequest("TOGGLE", null)))
                .thenReturn(new SmartHomeActionResult(true, "user-1", "TOGGLE", "ok", deviceView(), Instant.now()));

        ResponseEntity<?> response = controller.executeAction("user-1", "kitchen_light", new SmartHomeActionRequest("TOGGLE", null));

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void getDeviceReturnsNotFoundForUnknownDevice() {
        when(smartHomeService.getDevice("user-1", "missing"))
                .thenThrow(new SmartHomeDeviceNotFoundException("missing"));

        ResponseEntity<?> response = controller.getDevice("user-1", "missing");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("DEVICE_NOT_FOUND", ((Map<?, ?>) response.getBody()).get("error"));
    }

    @Test
    void getDeviceReturnsDeviceForValidRequest() {
        when(smartHomeService.getDevice("user-1", "kitchen_light")).thenReturn(deviceView());

        ResponseEntity<?> response = controller.getDevice("user-1", "kitchen_light");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(deviceView(), response.getBody());
    }

    @Test
    void getDeviceRejectsMissingDelegatedUserContext() {
        ResponseEntity<?> response = controller.getDevice(null, "kitchen_light");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("MISSING_USER_CONTEXT", ((Map<?, ?>) response.getBody()).get("error"));
    }

    @Test
    void executeActionReturnsBadRequestForValidationError() {
        when(smartHomeService.executeAction("user-1", "kitchen_light", new SmartHomeActionRequest("SET_BRIGHTNESS", "500")))
                .thenThrow(new SmartHomeValidationException("brightness must be between 0 and 100"));

        ResponseEntity<?> response = controller.executeAction("user-1", "kitchen_light", new SmartHomeActionRequest("SET_BRIGHTNESS", "500"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_ACTION", ((Map<?, ?>) response.getBody()).get("error"));
    }

    @Test
    void executeActionReturnsNotFoundForUnknownDevice() {
        when(smartHomeService.executeAction("user-1", "missing", new SmartHomeActionRequest("TOGGLE", null)))
                .thenThrow(new SmartHomeDeviceNotFoundException("missing"));

        ResponseEntity<?> response = controller.executeAction("user-1", "missing", new SmartHomeActionRequest("TOGGLE", null));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("DEVICE_NOT_FOUND", ((Map<?, ?>) response.getBody()).get("error"));
    }

    @Test
    void listDevicesRejectsMissingDelegatedUserContext() {
        ResponseEntity<?> response = controller.listDevices(" ");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("MISSING_USER_CONTEXT", ((Map<?, ?>) response.getBody()).get("error"));
    }

    @Test
    void catalogReturnsAllDeviceDefinitions() {
        List<SmartHomeDeviceDefinition> definitions = List.of(deviceDefinition());
        when(deviceCatalog.all()).thenReturn(definitions);

        assertEquals(definitions, controller.catalog());
    }

    @Test
    void registerDeviceRejectsBlankId() {
        SmartHomeController.DeviceRegistration body = new SmartHomeController.DeviceRegistration(
                " ", "Name", "Room", SmartHomeDeviceType.LIGHT, List.of(), Map.of());

        ResponseEntity<?> response = controller.registerDevice(body);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_DEVICE", ((Map<?, ?>) response.getBody()).get("error"));
    }

    @Test
    void registerDeviceRejectsMissingType() {
        SmartHomeController.DeviceRegistration body = new SmartHomeController.DeviceRegistration(
                "valid_id", "Name", "Room", null, List.of(), Map.of());

        ResponseEntity<?> response = controller.registerDevice(body);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_DEVICE", ((Map<?, ?>) response.getBody()).get("error"));
    }

    @Test
    void registerDeviceAppliesDefaultsWhenOptionalFieldsAreNull() {
        SmartHomeController.DeviceRegistration body = new SmartHomeController.DeviceRegistration(
                "new_device", null, null, SmartHomeDeviceType.LIGHT, null, null);
        when(deviceCatalog.register(any(SmartHomeDeviceDefinition.class))).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<?> response = controller.registerDevice(body);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        ArgumentCaptor<SmartHomeDeviceDefinition> captor = ArgumentCaptor.forClass(SmartHomeDeviceDefinition.class);
        verify(deviceCatalog).register(captor.capture());
        SmartHomeDeviceDefinition captured = captor.getValue();
        assertEquals("new_device", captured.id());
        assertEquals("new_device", captured.displayName());
        assertEquals("Home", captured.room());
        assertEquals(List.of(), captured.supportedActions());
        assertEquals(Map.of(), captured.defaultState());
    }

    @Test
    void registerDeviceUsesProvidedValuesWhenPresent() {
        SmartHomeController.DeviceRegistration body = new SmartHomeController.DeviceRegistration(
                "id2", "Name2", "Room2", SmartHomeDeviceType.THERMOSTAT,
                List.of("SET_TEMPERATURE"), Map.of("power", true));
        when(deviceCatalog.register(any(SmartHomeDeviceDefinition.class))).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<?> response = controller.registerDevice(body);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        ArgumentCaptor<SmartHomeDeviceDefinition> captor = ArgumentCaptor.forClass(SmartHomeDeviceDefinition.class);
        verify(deviceCatalog).register(captor.capture());
        SmartHomeDeviceDefinition captured = captor.getValue();
        assertEquals("Name2", captured.displayName());
        assertEquals("Room2", captured.room());
        assertEquals(List.of("SET_TEMPERATURE"), captured.supportedActions());
        assertEquals(Map.of("power", true), captured.defaultState());
    }

    @Test
    void removeDeviceReturnsNoContentWhenFound() {
        when(deviceCatalog.remove("kitchen_light")).thenReturn(true);

        ResponseEntity<?> response = controller.removeDevice("kitchen_light");

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }

    @Test
    void removeDeviceReturnsNotFoundWhenMissing() {
        when(deviceCatalog.remove("missing")).thenReturn(false);

        ResponseEntity<?> response = controller.removeDevice("missing");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("DEVICE_NOT_FOUND", ((Map<?, ?>) response.getBody()).get("error"));
    }

    @Test
    void getSupportedActionsReturnsActionsAndDescription() {
        when(smartHomeService.supportedActions()).thenReturn(List.of("TOGGLE", "LOCK"));

        ResponseEntity<Map<String, Object>> response = controller.getSupportedActions();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(List.of("TOGGLE", "LOCK"), response.getBody().get("supportedActions"));
        assertEquals("Stateful smart-home control with local mock and MQTT transport support",
                response.getBody().get("description"));
    }

    @Test
    void resolveIntentDelegatesToIntentServiceAndReturnsResolution() {
        SmartHomeIntentResolution resolution = new SmartHomeIntentResolution(
                "turn on the kitchen light", IntentMatchStatus.RESOLVED, 0.9, "TURN_ON", null,
                deviceDefinition(), List.of(), "Resolved device and action; not executed (planning only)");
        when(intentService.resolve("turn on the kitchen light")).thenReturn(resolution);

        ResponseEntity<SmartHomeIntentResolution> response =
                controller.resolveIntent(new SmartHomeController.IntentQuery("turn on the kitchen light"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(resolution, response.getBody());
    }

    @Test
    void resolveIntentHandlesNullBodyGracefully() {
        SmartHomeIntentResolution resolution = new SmartHomeIntentResolution(
                "", IntentMatchStatus.UNKNOWN, 0.0, null, null, null, List.of(), "Empty utterance");
        when(intentService.resolve(null)).thenReturn(resolution);

        ResponseEntity<SmartHomeIntentResolution> response = controller.resolveIntent(null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(resolution, response.getBody());
    }

    @Test
    void scenesReturnsAllScenes() {
        List<SmartHomeScene> scenes = List.of(new SmartHomeScene("movie_night", List.of()));
        when(sceneService.all()).thenReturn(scenes);

        assertEquals(scenes, controller.scenes());
    }

    @Test
    void createSceneRejectsNullScene() {
        ResponseEntity<?> response = controller.createScene(null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_SCENE", ((Map<?, ?>) response.getBody()).get("error"));
    }

    @Test
    void createSceneRejectsBlankName() {
        ResponseEntity<?> response = controller.createScene(new SmartHomeScene(" ", List.of()));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_SCENE", ((Map<?, ?>) response.getBody()).get("error"));
    }

    @Test
    void createSceneSavesValidScene() {
        SmartHomeScene scene = new SmartHomeScene("movie_night",
                List.of(new SmartHomeScene.SceneStep("kitchen_light", "TURN_OFF", null)));
        when(sceneService.save(scene)).thenReturn(scene);

        ResponseEntity<?> response = controller.createScene(scene);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(scene, response.getBody());
    }

    @Test
    void deleteSceneReturnsNoContentWhenFound() {
        when(sceneService.remove("movie_night")).thenReturn(true);

        ResponseEntity<?> response = controller.deleteScene("movie_night");

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }

    @Test
    void deleteSceneReturnsNotFoundWhenMissing() {
        when(sceneService.remove("missing")).thenReturn(false);

        ResponseEntity<?> response = controller.deleteScene("missing");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("SCENE_NOT_FOUND", ((Map<?, ?>) response.getBody()).get("error"));
    }

    @Test
    void activateSceneRejectsMissingDelegatedUserContext() {
        ResponseEntity<?> response = controller.activateScene(" ", "movie_night");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("MISSING_USER_CONTEXT", ((Map<?, ?>) response.getBody()).get("error"));
    }

    @Test
    void activateSceneReturnsNotFoundForUnknownScene() {
        when(sceneService.find("missing")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.activateScene("user-1", "missing");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("SCENE_NOT_FOUND", ((Map<?, ?>) response.getBody()).get("error"));
    }

    @Test
    void activateSceneAppliesStepsAndCollectsSuccessAndErrorResults() {
        SmartHomeScene.SceneStep okStep = new SmartHomeScene.SceneStep("kitchen_light", "TURN_OFF", null);
        SmartHomeScene.SceneStep failingStep = new SmartHomeScene.SceneStep("front_door_lock", "LOCK", null);
        SmartHomeScene scene = new SmartHomeScene("night", List.of(okStep, failingStep));
        when(sceneService.find("night")).thenReturn(Optional.of(scene));

        SmartHomeActionResult okResult = new SmartHomeActionResult(
                true, "user-1", "TURN_OFF", "ok", deviceView(), Instant.now());
        when(smartHomeService.executeAction("user-1", "kitchen_light", new SmartHomeActionRequest("TURN_OFF", null)))
                .thenReturn(okResult);
        when(smartHomeService.executeAction("user-1", "front_door_lock", new SmartHomeActionRequest("LOCK", null)))
                .thenThrow(new SmartHomeValidationException("Action LOCK is not supported for device front_door_lock"));

        ResponseEntity<?> response = controller.activateScene("user-1", "night");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals("night", body.get("scene"));
        assertEquals(2, body.get("applied"));
        List<?> results = (List<?>) body.get("results");
        assertEquals(okResult, results.get(0));
        Map<?, ?> errorEntry = (Map<?, ?>) results.get(1);
        assertEquals("front_door_lock", errorEntry.get("deviceId"));
        assertEquals("Action LOCK is not supported for device front_door_lock", errorEntry.get("error"));
    }

    @Test
    void activateSceneFallsBackToExceptionClassNameWhenMessageIsNull() {
        SmartHomeScene.SceneStep step = new SmartHomeScene.SceneStep("kitchen_light", "TURN_ON", null);
        SmartHomeScene scene = new SmartHomeScene("scene2", List.of(step));
        when(sceneService.find("scene2")).thenReturn(Optional.of(scene));
        when(smartHomeService.executeAction("user-1", "kitchen_light", new SmartHomeActionRequest("TURN_ON", null)))
                .thenThrow(new RuntimeException());

        ResponseEntity<?> response = controller.activateScene("user-1", "scene2");

        Map<?, ?> body = (Map<?, ?>) response.getBody();
        List<?> results = (List<?>) body.get("results");
        Map<?, ?> errorEntry = (Map<?, ?>) results.get(0);
        assertEquals("kitchen_light", errorEntry.get("deviceId"));
        assertEquals("RuntimeException", errorEntry.get("error"));
    }

    @Test
    void activateSceneHandlesSceneWithNullSteps() {
        SmartHomeScene scene = new SmartHomeScene("empty_scene", null);
        when(sceneService.find("empty_scene")).thenReturn(Optional.of(scene));

        ResponseEntity<?> response = controller.activateScene("user-1", "empty_scene");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals(0, body.get("applied"));
        assertTrue(((List<?>) body.get("results")).isEmpty());
    }

    @Test
    void activateSceneRecordsAHistoryEntry() {
        SmartHomeScene.SceneStep step = new SmartHomeScene.SceneStep("kitchen_light", "TURN_ON", null);
        SmartHomeScene scene = new SmartHomeScene("night", List.of(step));
        when(sceneService.find("night")).thenReturn(Optional.of(scene));
        when(smartHomeService.executeAction("user-1", "kitchen_light", new SmartHomeActionRequest("TURN_ON", null)))
                .thenReturn(new SmartHomeActionResult(true, "user-1", "TURN_ON", "ok", deviceView(), Instant.now()));
        when(clock.instant()).thenReturn(Instant.parse("2026-03-14T10:30:00Z"));

        controller.activateScene("user-1", "night");

        ArgumentCaptor<SmartHomeSceneActivation> captor = ArgumentCaptor.forClass(SmartHomeSceneActivation.class);
        verify(sceneHistoryService).record(captor.capture());
        SmartHomeSceneActivation activation = captor.getValue();
        assertEquals("night", activation.sceneName());
        assertEquals("user-1", activation.userId());
        assertEquals(1, activation.stepCount());
        assertEquals(1, activation.successCount());
        assertEquals(0, activation.failureCount());
    }

    @Test
    void sceneHistoryReturnsRecentActivations() {
        List<SmartHomeSceneActivation> activations = List.of(new SmartHomeSceneActivation(
                "night", "user-1", Instant.now(), 1, 1, 0));
        when(sceneHistoryService.recent(50)).thenReturn(activations);

        assertEquals(activations, controller.sceneHistory(50));
    }

    @Test
    void groupsReturnsAllGroups() {
        List<SmartHomeGroup> groups = List.of(new SmartHomeGroup("lights", "Lights", List.of("kitchen_light")));
        when(groupService.all()).thenReturn(groups);

        assertEquals(groups, controller.groups());
    }

    @Test
    void createGroupRejectsBlankId() {
        ResponseEntity<?> response = controller.createGroup(new SmartHomeGroup(" ", "Lights", List.of()));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_GROUP", ((Map<?, ?>) response.getBody()).get("error"));
    }

    @Test
    void createGroupSavesValidGroup() {
        SmartHomeGroup group = new SmartHomeGroup("lights", "Lights", List.of("kitchen_light"));
        when(groupService.save(group)).thenReturn(group);

        ResponseEntity<?> response = controller.createGroup(group);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(group, response.getBody());
    }

    @Test
    void deleteGroupReturnsNotFoundWhenMissing() {
        when(groupService.remove("missing")).thenReturn(false);

        ResponseEntity<?> response = controller.deleteGroup("missing");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void addDeviceToGroupReturnsNotFoundForUnknownGroup() {
        when(groupService.addDevice("missing", "kitchen_light")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.addDeviceToGroup("missing", "kitchen_light");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void actOnGroupAppliesActionToEveryMemberDevice() {
        SmartHomeGroup group = new SmartHomeGroup("lights", "Lights", List.of("kitchen_light", "desk_lamp"));
        when(groupService.find("lights")).thenReturn(Optional.of(group));
        when(smartHomeService.executeAction("user-1", "kitchen_light", new SmartHomeActionRequest("TURN_OFF", null)))
                .thenReturn(new SmartHomeActionResult(true, "user-1", "TURN_OFF", "ok", deviceView(), Instant.now()));
        when(smartHomeService.executeAction("user-1", "desk_lamp", new SmartHomeActionRequest("TURN_OFF", null)))
                .thenThrow(new SmartHomeValidationException("boom"));

        ResponseEntity<?> response = controller.actOnGroup("user-1", "lights", new SmartHomeActionRequest("TURN_OFF", null));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals(2, body.get("applied"));
        List<?> results = (List<?>) body.get("results");
        Map<?, ?> errorEntry = (Map<?, ?>) results.get(1);
        assertEquals("desk_lamp", errorEntry.get("deviceId"));
        assertEquals("boom", errorEntry.get("error"));
    }

    @Test
    void actOnGroupReturnsNotFoundForUnknownGroup() {
        when(groupService.find("missing")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.actOnGroup("user-1", "missing", new SmartHomeActionRequest("TURN_OFF", null));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void actOnGroupRejectsMissingDelegatedUserContext() {
        ResponseEntity<?> response = controller.actOnGroup(" ", "lights", new SmartHomeActionRequest("TURN_OFF", null));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("MISSING_USER_CONTEXT", ((Map<?, ?>) response.getBody()).get("error"));
    }

    @Test
    void roomsReturnsAllRooms() {
        List<SmartHomeRoom> rooms = List.of(new SmartHomeRoom("kitchen", "Kitchen", List.of("kitchen_light")));
        when(roomService.all()).thenReturn(rooms);

        assertEquals(rooms, controller.rooms());
    }

    @Test
    void createRoomRejectsBlankId() {
        ResponseEntity<?> response = controller.createRoom(new SmartHomeController.RoomRequest(" ", "Kitchen"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_ROOM", ((Map<?, ?>) response.getBody()).get("error"));
    }

    @Test
    void createRoomUsesIdAsNameWhenNameIsBlank() {
        SmartHomeRoom room = new SmartHomeRoom("kitchen", "kitchen", List.of());
        when(roomService.createOrUpdate("kitchen", "kitchen")).thenReturn(room);

        ResponseEntity<?> response = controller.createRoom(new SmartHomeController.RoomRequest("kitchen", " "));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(room, response.getBody());
    }

    @Test
    void assignDeviceToRoomReturnsUpdatedRoom() {
        SmartHomeRoom room = new SmartHomeRoom("kitchen", "Kitchen", List.of("kitchen_light"));
        when(roomService.assignDevice("kitchen", "kitchen_light")).thenReturn(Optional.of(room));

        ResponseEntity<?> response = controller.assignDeviceToRoom("kitchen", "kitchen_light");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(room, response.getBody());
    }

    @Test
    void assignDeviceToRoomReturnsNotFoundForUnknownRoom() {
        when(roomService.assignDevice("missing", "kitchen_light")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.assignDeviceToRoom("missing", "kitchen_light");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void actOnRoomAppliesActionToEveryAssignedDevice() {
        SmartHomeRoom room = new SmartHomeRoom("kitchen", "Kitchen", List.of("kitchen_light"));
        when(roomService.find("kitchen")).thenReturn(Optional.of(room));
        when(smartHomeService.executeAction("user-1", "kitchen_light", new SmartHomeActionRequest("TURN_ON", null)))
                .thenReturn(new SmartHomeActionResult(true, "user-1", "TURN_ON", "ok", deviceView(), Instant.now()));

        ResponseEntity<?> response = controller.actOnRoom("user-1", "kitchen", new SmartHomeActionRequest("TURN_ON", null));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals(1, body.get("applied"));
    }

    @Test
    void actOnRoomReturnsNotFoundForUnknownRoom() {
        when(roomService.find("missing")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.actOnRoom("user-1", "missing", new SmartHomeActionRequest("TURN_ON", null));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void ingestSensorReadingReturnsStoredReading() {
        SmartHomeSensorReading reading = new SmartHomeSensorReading(
                "hall_thermostat", "TEMPERATURE", 21.5, "C", Instant.parse("2026-03-14T10:30:00Z"));
        when(sensorService.ingest("hall_thermostat", "temperature", 21.5, "C")).thenReturn(reading);

        ResponseEntity<?> response = controller.ingestSensorReading(
                "hall_thermostat", new SmartHomeController.SensorReadingRequest("temperature", 21.5, "C"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(reading, response.getBody());
    }

    @Test
    void ingestSensorReadingReturnsNotFoundForUnknownDevice() {
        when(sensorService.ingest("missing", "temperature", 1.0, null))
                .thenThrow(new SmartHomeDeviceNotFoundException("missing"));

        ResponseEntity<?> response = controller.ingestSensorReading(
                "missing", new SmartHomeController.SensorReadingRequest("temperature", 1.0, null));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void ingestSensorReadingReturnsBadRequestForInvalidReading() {
        when(sensorService.ingest("hall_thermostat", " ", 1.0, null))
                .thenThrow(new SmartHomeValidationException("metric is required"));

        ResponseEntity<?> response = controller.ingestSensorReading(
                "hall_thermostat", new SmartHomeController.SensorReadingRequest(" ", 1.0, null));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_READING", ((Map<?, ?>) response.getBody()).get("error"));
    }

    @Test
    void latestSensorReadingsWithMetricReturnsSingleReading() {
        SmartHomeSensorReading reading = new SmartHomeSensorReading(
                "hall_thermostat", "TEMPERATURE", 21.5, "C", Instant.now());
        when(sensorService.latest("hall_thermostat", "temperature")).thenReturn(Optional.of(reading));

        ResponseEntity<?> response = controller.latestSensorReadings("hall_thermostat", "temperature");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(reading, response.getBody());
    }

    @Test
    void latestSensorReadingsWithoutMetricReturnsAllLatest() {
        List<SmartHomeSensorReading> readings = List.of(new SmartHomeSensorReading(
                "hall_thermostat", "TEMPERATURE", 21.5, "C", Instant.now()));
        when(sensorService.latestForDevice("hall_thermostat")).thenReturn(readings);

        ResponseEntity<?> response = controller.latestSensorReadings("hall_thermostat", null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(readings, response.getBody());
    }

    @Test
    void sensorReadingHistoryDelegatesToService() {
        List<SmartHomeSensorReading> readings = List.of(new SmartHomeSensorReading(
                "hall_thermostat", "TEMPERATURE", 21.5, "C", Instant.now()));
        when(sensorService.history("hall_thermostat", "temperature")).thenReturn(readings);

        assertEquals(readings, controller.sensorReadingHistory("hall_thermostat", "temperature"));
    }

    @Test
    void automationRulesReturnsAllRules() {
        List<SmartHomeAutomationRule> rules = List.of(automationRule());
        when(automationRuleRegistry.all()).thenReturn(rules);

        assertEquals(rules, controller.automationRules());
    }

    @Test
    void createAutomationRuleRejectsMissingRequiredFields() {
        SmartHomeAutomationRule invalid = new SmartHomeAutomationRule(
                "rule-1", "Rule", null, null, null, null, null, null, false, true);

        ResponseEntity<?> response = controller.createAutomationRule(invalid);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_RULE", ((Map<?, ?>) response.getBody()).get("error"));
    }

    @Test
    void createAutomationRuleSavesValidRule() {
        SmartHomeAutomationRule rule = automationRule();
        when(automationRuleRegistry.save(rule)).thenReturn(rule);

        ResponseEntity<?> response = controller.createAutomationRule(rule);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(rule, response.getBody());
    }

    @Test
    void deleteAutomationRuleReturnsNotFoundWhenMissing() {
        when(automationRuleRegistry.remove("missing")).thenReturn(false);

        ResponseEntity<?> response = controller.deleteAutomationRule("missing");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("RULE_NOT_FOUND", ((Map<?, ?>) response.getBody()).get("error"));
    }

    @Test
    void scanForDevicesDelegatesToDiscoveryService() {
        SmartHomeDiscoveryResult result = new SmartHomeDiscoveryResult(false, "tcp://mosquitto:1883", List.of(), "stub");
        when(discoveryService.scan()).thenReturn(result);

        assertEquals(result, controller.scanForDevices());
    }

    private static SmartHomeAutomationRule automationRule() {
        return new SmartHomeAutomationRule(
                "rule-1", "Motion turns on kitchen light", "hall_motion",
                SmartHomeTriggerEvent.MOTION_DETECTED, null, "kitchen_light", "TURN_ON", null,
                false, true);
    }

    private SmartHomeDeviceView deviceView() {
        return new SmartHomeDeviceView(
                "kitchen_light",
                "Kitchen Light",
                "Kitchen",
                SmartHomeDeviceType.LIGHT,
                List.of("TURN_ON", "TURN_OFF", "TOGGLE"),
                Map.of("power", true),
                "mock",
                Instant.parse("2026-03-14T10:30:00Z"));
    }

    private SmartHomeDeviceDefinition deviceDefinition() {
        return new SmartHomeDeviceDefinition(
                "kitchen_light",
                "Kitchen Light",
                "Kitchen",
                SmartHomeDeviceType.LIGHT,
                List.of("TURN_ON", "TURN_OFF", "TOGGLE"),
                Map.of("power", true));
    }
}
