package org.jarvis.apigateway.controller;

import org.jarvis.apigateway.client.SmartHomeClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmartHomeProxyControllerTest {

    @Mock
    private SmartHomeClient smartHomeClient;

    @InjectMocks
    private SmartHomeProxyController controller;

    @Test
    void listDevicesDelegatesToSmartHomeClient() {
        when(smartHomeClient.listDevices()).thenReturn(ResponseEntity.ok("[]"));

        ResponseEntity<String> response = controller.listDevices();

        assertEquals(200, response.getStatusCode().value());
        verify(smartHomeClient).listDevices();
    }

    @Test
    void deviceActionDelegatesToSmartHomeClient() {
        when(smartHomeClient.deviceAction("kitchen_light", Map.of("action", "TOGGLE")))
                .thenReturn(ResponseEntity.ok("{\"success\":true}"));

        ResponseEntity<String> response = controller.deviceAction("kitchen_light", Map.of("action", "TOGGLE"));

        assertEquals(200, response.getStatusCode().value());
        verify(smartHomeClient).deviceAction("kitchen_light", Map.of("action", "TOGGLE"));
    }
}
