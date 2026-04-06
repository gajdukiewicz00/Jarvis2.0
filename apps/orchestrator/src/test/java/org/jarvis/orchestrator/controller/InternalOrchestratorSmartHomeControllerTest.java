package org.jarvis.orchestrator.controller;

import org.jarvis.orchestrator.client.PcControlClient;
import org.jarvis.orchestrator.client.SmartHomeClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InternalOrchestratorSmartHomeControllerTest {

    @Mock
    private SmartHomeClient smartHomeClient;

    @Mock
    private PcControlClient pcControlClient;

    @InjectMocks
    private InternalOrchestratorSmartHomeController controller;

    @Test
    void dispatchSmartHomeActionRoutesToClient() {
        ReflectionTestUtils.setField(controller, "smartHomeUrl", "https://smart-home-service.jarvis.svc.cluster.local:8086");
        when(smartHomeClient.executeAction(
                "user-42",
                "kitchen_light",
                new SmartHomeClient.ActionRequest("TURN_ON", "warm_white")))
                        .thenReturn(new SmartHomeClient.ActionResult(
                                true,
                                "user-42",
                                "TURN_ON",
                                "ok",
                                new SmartHomeClient.DeviceView(
                                        "kitchen_light",
                                        "Kitchen Light",
                                        "Kitchen",
                                        "LIGHT",
                                        List.of("TURN_ON"),
                                        Map.of("power", true),
                                        "mock",
                                        "2026-03-23T00:00:00Z"),
                                "2026-03-23T00:00:00Z"));

        ResponseEntity<?> response = controller.dispatchSmartHomeAction(Map.of(
                "userId", "user-42",
                "deviceId", "kitchen_light",
                "action", "TURN_ON",
                "payload", "warm_white"));

        assertEquals(200, response.getStatusCode().value());
        verify(smartHomeClient).executeAction(
                "user-42",
                "kitchen_light",
                new SmartHomeClient.ActionRequest("TURN_ON", "warm_white"));
    }

    @Test
    void dispatchPcActionRoutesToClient() {
        ReflectionTestUtils.setField(controller, "pcControlUrl", "https://pc-control.jarvis.svc.cluster.local:8084");

        ResponseEntity<?> response = controller.dispatchPcAction(Map.of(
                "userId", "user-42",
                "actionType", "NOTIFY",
                "parameters", Map.of(
                        "title", "TLS",
                        "message", "smoke-user-42")));

        assertEquals(200, response.getStatusCode().value());
        verify(pcControlClient).executeAction(eq("user-42"), eq(new PcControlClient.ActionRequest(
                "NOTIFY",
                Map.of("title", "TLS", "message", "smoke-user-42"))));
    }
}
