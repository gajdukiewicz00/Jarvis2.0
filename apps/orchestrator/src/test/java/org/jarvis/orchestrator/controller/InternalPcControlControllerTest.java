package org.jarvis.orchestrator.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.jarvis.orchestrator.service.impl.OrchestratorServiceImpl;
import org.junit.jupiter.api.Test;

class InternalPcControlControllerTest {

    private final OrchestratorServiceImpl service = mock(OrchestratorServiceImpl.class);
    private final InternalPcControlController controller = new InternalPcControlController(service);

    @Test
    void forwardsActionToWorkingDispatchAndReturnsResult() {
        when(service.dispatchPcActionForClient(eq("VOLUME_UP"), any(), eq("2"), eq("corr-1")))
                .thenReturn(Map.of(
                        "status", "executed",
                        "executorFound", true,
                        "executionSucceeded", true,
                        "executionFailed", false));

        Map<String, Object> result = controller.action(new InternalPcControlController.PcActionRequest(
                "VOLUME_UP", Map.of("delta", 10), "2", "corr-1"));

        assertEquals("executed", result.get("status"));
        assertEquals(true, result.get("executionSucceeded"));
        verify(service).dispatchPcActionForClient(eq("VOLUME_UP"), any(), eq("2"), eq("corr-1"));
    }

    @Test
    void rejectsMissingActionWithInvalidPayloadWithoutDispatching() {
        Map<String, Object> result = controller.action(new InternalPcControlController.PcActionRequest(
                null, Map.of(), "2", "corr-2"));

        assertEquals("invalid_request", result.get("status"));
        assertEquals(true, result.get("executionFailed"));
        assertTrue(String.valueOf(result.get("failureReason")).contains("INVALID_PAYLOAD"));
        verify(service, never()).dispatchPcActionForClient(any(), any(), any(), any());
    }
}
