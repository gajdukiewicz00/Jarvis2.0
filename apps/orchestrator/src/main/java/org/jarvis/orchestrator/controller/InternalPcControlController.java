package org.jarvis.orchestrator.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.orchestrator.service.impl.OrchestratorServiceImpl;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Internal passthrough that lets voice-gateway execute PC-control actions through the
 * orchestrator's working api-gateway → desktop WebSocket path.
 *
 * <p>Why this exists: voice-gateway is NetworkPolicy-blocked from calling
 * {@code api-gateway:8080/internal/pc-control/action} directly (only the ingress
 * controller and the orchestrator are allowed). The orchestrator IS allowed to reach
 * api-gateway. So instead of duplicating a separate (blocked) dispatch path, voice-gateway
 * posts the SAME request here and the orchestrator forwards it via the exact
 * {@code dispatchPcAction} the intent handlers use — including its host-pc-control fallback.
 * The response shape matches api-gateway's so voice-gateway parses it unchanged.
 */
@Slf4j
@RestController
@RequestMapping("/internal/pc-control")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SVC_INTERNAL')")
public class InternalPcControlController {

    private final OrchestratorServiceImpl orchestratorService;

    @PostMapping("/action")
    public Map<String, Object> action(@RequestBody PcActionRequest request) {
        String action = request.action();
        String correlationId = request.correlationId() != null && !request.correlationId().isBlank()
                ? request.correlationId()
                : "N/A";
        if (action == null || action.isBlank()) {
            log.warn("🎛️ Voice PC passthrough rejected: missing action, correlationId={}", correlationId);
            return Map.of(
                    "status", "invalid_request",
                    "executorFound", false,
                    "executionAttempted", false,
                    "executionSucceeded", false,
                    "executionFailed", true,
                    "failureReason", "INVALID_PAYLOAD: action is required",
                    "message", "INVALID_PAYLOAD: action is required");
        }
        log.info("🎛️ Voice PC passthrough: action={}, userId={}, correlationId={}, params={}",
                action, request.userId(), correlationId, request.params());
        Map<String, Object> result = orchestratorService.dispatchPcActionForClient(
                action, request.params(), request.userId(), correlationId);
        log.info(
                "🎛️ Voice PC passthrough result: action={}, correlationId={}, status={}, executionSucceeded={}, failureReason={}",
                action, correlationId, result.get("status"), result.get("executionSucceeded"),
                result.get("failureReason"));
        return result;
    }

    public record PcActionRequest(
            String action, Map<String, Object> params, String userId, String correlationId) {
    }
}
