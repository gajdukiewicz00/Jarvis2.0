package org.jarvis.pccontrol.controller;

import org.jarvis.pccontrol.service.SystemControlService;
import org.jarvis.pccontrol.service.TimerLimitExceededException;
import org.jarvis.pccontrol.service.TimerSchedulerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PcControlControllerTest {

    @Mock
    private SystemControlService systemControlService;

    @Mock
    private TimerSchedulerService timerSchedulerService;

    @InjectMocks
    private PcControlController pcControlController;

    @Test
    void shouldReturnTooManyRequestsWhenTimerLimitReached() {
        when(timerSchedulerService.scheduleTimer(eq(15), any(Runnable.class)))
                .thenThrow(new TimerLimitExceededException("Too many active timers (2)"));

        PcControlController.ActionRequest request = new PcControlController.ActionRequest(
                "SYSTEM_COMMAND",
                Map.of("command", "timer", "args", "15")
        );

        ResponseEntity<Map<String, Object>> response = pcControlController.executeAction(request);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertEquals("TIMER_LIMIT_EXCEEDED", response.getBody().get("error"));
    }

    @Test
    void shouldReturnTimerIdWhenTimerAccepted() {
        when(timerSchedulerService.scheduleTimer(eq(5), any(Runnable.class))).thenReturn("timer-123");

        PcControlController.ActionRequest request = new PcControlController.ActionRequest(
                "SYSTEM_COMMAND",
                Map.of("command", "timer", "args", "5")
        );

        ResponseEntity<Map<String, Object>> response = pcControlController.executeAction(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("timer-123", response.getBody().get("timerId"));
        assertEquals(true, response.getBody().get("success"));
    }
}
