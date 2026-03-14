package org.jarvis.pccontrol.controller;

import org.jarvis.pccontrol.model.PcActionExecutionStatus;
import org.jarvis.pccontrol.model.PcActionRequest;
import org.jarvis.pccontrol.model.PcActionResult;
import org.jarvis.pccontrol.service.PcActionExecutionService;
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
class PcControlControllerTest {

    @Mock
    private PcActionExecutionService executionService;

    @InjectMocks
    private PcControlController pcControlController;

    @Test
    void shouldReturnTooManyRequestsWhenTimerLimitReached() {
        when(executionService.execute(new PcActionRequest(
                "SYSTEM_COMMAND",
                Map.of("command", "timer", "args", "15")
        ))).thenReturn(new PcActionResult(
                false,
                "SYSTEM_COMMAND",
                PcActionExecutionStatus.REJECTED,
                "Too many active timers (2)",
                "TIMER_LIMIT_EXCEEDED",
                Map.of("command", "timer"),
                List.of(),
                Instant.now()
        ));

        PcActionRequest request = new PcActionRequest(
                "SYSTEM_COMMAND",
                Map.of("command", "timer", "args", "15")
        );

        ResponseEntity<PcActionResult> response = pcControlController.executeAction(request);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertEquals("TIMER_LIMIT_EXCEEDED", response.getBody().errorCode());
    }

    @Test
    void shouldReturnTimerIdWhenTimerAccepted() {
        when(executionService.execute(new PcActionRequest(
                "SYSTEM_COMMAND",
                Map.of("command", "timer", "args", "5")
        ))).thenReturn(new PcActionResult(
                true,
                "SYSTEM_COMMAND",
                PcActionExecutionStatus.SUCCESS,
                "Timer started",
                null,
                Map.of("timerId", "timer-123"),
                List.of(),
                Instant.now()
        ));

        PcActionRequest request = new PcActionRequest(
                "SYSTEM_COMMAND",
                Map.of("command", "timer", "args", "5")
        );

        ResponseEntity<PcActionResult> response = pcControlController.executeAction(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("timer-123", response.getBody().details().get("timerId"));
        assertEquals(true, response.getBody().success());
    }
}
