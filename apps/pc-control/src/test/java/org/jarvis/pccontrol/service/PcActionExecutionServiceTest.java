package org.jarvis.pccontrol.service;

import org.jarvis.pccontrol.model.PcActionExecutionStatus;
import org.jarvis.pccontrol.model.PcActionRequest;
import org.jarvis.pccontrol.model.PcActionResult;
import org.jarvis.pccontrol.security.CommandValidator;
import org.jarvis.pccontrol.service.impl.DefaultPcActionExecutionService;
import org.jarvis.pccontrol.service.impl.InMemoryPcScenarioRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PcActionExecutionServiceTest {

    @Mock
    private SystemControlService systemControlService;

    @Mock
    private TimerSchedulerService timerSchedulerService;

    private DefaultPcActionExecutionService service;

    @BeforeEach
    void setUp() {
        CommandValidator commandValidator = new CommandValidator();
        ReflectionTestUtils.setField(commandValidator, "allowedActions", List.of(
                "MEDIA_CONTROL",
                "VOLUME_UP",
                "VOLUME_DOWN",
                "SET_VOLUME",
                "VOLUME_SET",
                "MUTE",
                "UNMUTE",
                "PLAY_PAUSE",
                "PAUSE",
                "NEXT",
                "PREV",
                "OPEN_APP",
                "HOTKEY",
                "NOTIFY",
                "SYSTEM_COMMAND",
                "SCENARIO"
        ));
        service = new DefaultPcActionExecutionService(
                systemControlService,
                timerSchedulerService,
                commandValidator,
                new InMemoryPcScenarioRegistry());
    }

    @Test
    void executeScenarioReturnsStepResultsAndPartialSuccessWhenOneStepFails() throws Exception {
        doThrow(new IOException("code missing")).when(systemControlService).openApp("code");

        PcActionResult result = service.execute(new PcActionRequest("SCENARIO", Map.of("name", "work")));

        assertTrue(result.success());
        assertEquals(PcActionExecutionStatus.PARTIAL_SUCCESS, result.status());
        assertEquals("SCENARIO", result.actionType());
        assertEquals("work", result.details().get("scenario"));
        assertEquals(3, result.steps().size());
        assertEquals(PcActionExecutionStatus.FAILED, result.steps().get(0).status());
        assertEquals(PcActionExecutionStatus.SUCCESS, result.steps().get(1).status());
        assertEquals(PcActionExecutionStatus.SUCCESS, result.steps().get(2).status());

        verify(systemControlService).openApp("browser");
        verify(systemControlService).sendNotification("Work Mode", "Work scenario activated");
    }

    @Test
    void executeRejectsUnknownScenario() {
        PcActionResult result = service.execute(new PcActionRequest("SCENARIO", Map.of("name", "unknown")));

        assertFalse(result.success());
        assertEquals(PcActionExecutionStatus.REJECTED, result.status());
        assertEquals("UNKNOWN_SCENARIO", result.errorCode());
    }

    @Test
    void executeVolumeUpReturnsStructuredResult() {
        PcActionResult result = service.execute(new PcActionRequest("VOLUME_UP", Map.of("delta", "15")));

        assertTrue(result.success());
        assertEquals(PcActionExecutionStatus.SUCCESS, result.status());
        assertEquals(15, result.details().get("delta"));
        assertEquals("+", result.details().get("direction"));
    }

    @Test
    void executeLegacyBrowserScenarioUsesWindowAndMouseSteps() throws Exception {
        PcActionResult result = service.execute(new PcActionRequest("SCENARIO", Map.of("name", "legacy_browser_maximize")));

        assertTrue(result.success());
        assertEquals(PcActionExecutionStatus.SUCCESS, result.status());

        InOrder inOrder = inOrder(systemControlService);
        inOrder.verify(systemControlService).maximizeWindow("Opera");
        inOrder.verify(systemControlService).leftClick();
    }

    @Test
    void executeLegacyDrawingScenarioUsesMouseDragSteps() throws Exception {
        PcActionResult result = service.execute(new PcActionRequest("SCENARIO", Map.of("name", "legacy_draw_circle")));

        assertTrue(result.success());
        assertEquals(PcActionExecutionStatus.SUCCESS, result.status());

        InOrder inOrder = inOrder(systemControlService);
        inOrder.verify(systemControlService).moveMouseAbsolute(531, 64);
        inOrder.verify(systemControlService).leftClick();
        inOrder.verify(systemControlService).moveMouseAbsolute(158, 213);
        inOrder.verify(systemControlService).leftButtonDown();
        inOrder.verify(systemControlService).moveMouseAbsolute(447, 473);
        inOrder.verify(systemControlService).leftButtonUp();
    }
}
