package org.jarvis.pccontrol.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.pccontrol.model.PcActionExecutionStatus;
import org.jarvis.pccontrol.model.PcActionRequest;
import org.jarvis.pccontrol.model.PcActionResult;
import org.jarvis.pccontrol.service.PcActionExecutionService;
import org.jarvis.pccontrol.service.PcScenarioRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * PC control controller for system operations.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/pc")
@RequiredArgsConstructor
public class PcControlController {

    private final PcActionExecutionService executionService;
    private final PcScenarioRegistry scenarioRegistry;

    @PostMapping("/action")
    public ResponseEntity<PcActionResult> executeAction(@RequestBody PcActionRequest request) {
        log.info("Received PC action: type={}, parameters={}",
                request.actionType(), request.parameters());
        PcActionResult result = executionService.execute(request);
        return ResponseEntity.status(httpStatus(result)).body(result);
    }

    @GetMapping("/actions")
    public ResponseEntity<Map<String, Object>> getSupportedActions() {
        return ResponseEntity.ok(Map.of(
                "supportedActionTypes", java.util.List.of(
                        "MEDIA_CONTROL",
                        "VOLUME_UP",
                        "VOLUME_DOWN",
                        "SET_VOLUME",
                        "MUTE",
                        "UNMUTE",
                        "PLAY_PAUSE",
                        "PAUSE",
                        "NEXT",
                        "PREV",
                        "OPEN_APP",
                        "OPEN_URL",
                        "HOTKEY",
                        "TYPE_TEXT",
                        "WINDOW_FOCUS",
                        "NOTIFY",
                        "SCREENSHOT",
                        "LOCK_SCREEN",
                        "SYSTEM_COMMAND",
                        "SCENARIO"),
                "supportedScenarios", scenarioRegistry.all()));
    }

    private HttpStatus httpStatus(PcActionResult result) {
        if (result.status() == PcActionExecutionStatus.SUCCESS || result.status() == PcActionExecutionStatus.PARTIAL_SUCCESS) {
            return HttpStatus.OK;
        }
        if ("TIMER_LIMIT_EXCEEDED".equals(result.errorCode())) {
            return HttpStatus.TOO_MANY_REQUESTS;
        }
        if ("BLOCKED_ACTION".equals(result.errorCode())) {
            return HttpStatus.FORBIDDEN;
        }
        if (result.status() == PcActionExecutionStatus.REJECTED) {
            return HttpStatus.BAD_REQUEST;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
