package org.jarvis.smarthome.controller;

import org.jarvis.smarthome.model.SmartHomeActionRequest;
import org.jarvis.smarthome.model.SmartHomeActionResult;
import org.jarvis.smarthome.model.SmartHomeDeviceType;
import org.jarvis.smarthome.model.SmartHomeDeviceView;
import org.jarvis.smarthome.service.SmartHomeDeviceNotFoundException;
import org.jarvis.smarthome.service.SmartHomeService;
import org.jarvis.smarthome.service.SmartHomeValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmartHomeControllerTest {

    @Mock
    private SmartHomeService smartHomeService;

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
    void executeActionReturnsBadRequestForValidationError() {
        when(smartHomeService.executeAction("user-1", "kitchen_light", new SmartHomeActionRequest("SET_BRIGHTNESS", "500")))
                .thenThrow(new SmartHomeValidationException("brightness must be between 0 and 100"));

        ResponseEntity<?> response = controller.executeAction("user-1", "kitchen_light", new SmartHomeActionRequest("SET_BRIGHTNESS", "500"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_ACTION", ((Map<?, ?>) response.getBody()).get("error"));
    }

    @Test
    void listDevicesRejectsMissingDelegatedUserContext() {
        ResponseEntity<?> response = controller.listDevices(" ");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("MISSING_USER_CONTEXT", ((Map<?, ?>) response.getBody()).get("error"));
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
}
