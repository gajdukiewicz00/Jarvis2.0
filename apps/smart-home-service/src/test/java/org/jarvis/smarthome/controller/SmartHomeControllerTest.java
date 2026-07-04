package org.jarvis.smarthome.controller;

import org.jarvis.smarthome.model.SmartHomeActionRequest;
import org.jarvis.smarthome.model.SmartHomeActionResult;
import org.jarvis.smarthome.model.SmartHomeDeviceDefinition;
import org.jarvis.smarthome.model.SmartHomeDeviceType;
import org.jarvis.smarthome.model.SmartHomeDeviceView;
import org.jarvis.smarthome.model.SmartHomeScene;
import org.jarvis.smarthome.service.SmartHomeDeviceCatalog;
import org.jarvis.smarthome.service.SmartHomeDeviceNotFoundException;
import org.jarvis.smarthome.service.SmartHomeSceneService;
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
