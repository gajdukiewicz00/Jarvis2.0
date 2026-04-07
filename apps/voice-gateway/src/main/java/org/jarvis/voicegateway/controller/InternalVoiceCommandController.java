package org.jarvis.voicegateway.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.voicegateway.client.OrchestratorClient;
import org.jarvis.voicegateway.client.PcControlActionGateway;
import org.jarvis.voicegateway.client.SmartHomeActionGateway;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Internal controller for routing direct PC-control commands through voice-gateway.
 * This is used for operational smoke validation of the voice-gateway -> api-gateway path.
 */
@Slf4j
@RestController
@RequestMapping("/internal/voice")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SVC_INTERNAL')")
public class InternalVoiceCommandController {

    private final PcControlActionGateway pcControlActionGateway;
    private final OrchestratorClient orchestratorClient;
    private final SmartHomeActionGateway smartHomeActionGateway;

    @PostMapping("/pc-action")
    public ResponseEntity<?> dispatchPcAction(@RequestBody Map<String, Object> body) {
        String action = body.get("action") != null ? String.valueOf(body.get("action")) : null;
        Object paramsValue = body.get("params");
        String userId = body.get("userId") != null ? String.valueOf(body.get("userId")) : null;
        String correlationId = body.get("correlationId") != null ? String.valueOf(body.get("correlationId")) : null;

        if (action == null || action.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "action is required"));
        }

        Map<String, Object> params = paramsValue instanceof Map<?, ?> raw
                ? raw.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                        entry -> String.valueOf(entry.getKey()),
                        Map.Entry::getValue,
                        (left, right) -> right,
                        java.util.LinkedHashMap::new))
                : Map.of();

        log.info("Routing internal voice PC action: action={}, userId={}, correlationId={}, params={}",
                action, userId, correlationId, params);
        PcControlActionGateway.DispatchResult dispatchResult =
                pcControlActionGateway.dispatch(action, params, userId, correlationId);

        return ResponseEntity.ok(Map.of(
                "status", dispatchResult.status(),
                "action", action,
                "userId", userId != null ? userId : "",
                "correlationId", correlationId != null ? correlationId : "",
                "executorFound", dispatchResult.executorFound(),
                "executionAttempted", dispatchResult.executionAttempted(),
                "executionSucceeded", dispatchResult.executionSucceeded(),
                "executionFailed", dispatchResult.executionFailed(),
                "failureReason", dispatchResult.failureReason() != null ? dispatchResult.failureReason() : ""));
    }

    @PostMapping("/orchestrator-intent")
    public ResponseEntity<?> dispatchOrchestratorIntent(@RequestBody Map<String, Object> body) {
        String intent = body.get("intent") != null ? String.valueOf(body.get("intent")) : null;
        String correlationId = body.get("correlationId") != null ? String.valueOf(body.get("correlationId")) : null;
        String language = body.get("language") != null ? String.valueOf(body.get("language")) : "ru";
        String originalText = body.get("originalText") != null ? String.valueOf(body.get("originalText")) : null;
        String userId = body.get("userId") != null ? String.valueOf(body.get("userId")) : null;

        if (intent == null || intent.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "intent is required"));
        }
        if (correlationId == null || correlationId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "correlationId is required"));
        }

        log.info("Routing internal voice orchestrator intent: intent={}, correlationId={}, userId={}, language={}",
                intent, correlationId, userId, language);
        String response = orchestratorClient.sendIntent(intent, Map.of(), language, correlationId, originalText, userId);

        return ResponseEntity.ok(Map.of(
                "status", "dispatched",
                "intent", intent,
                "correlationId", correlationId,
                "response", response != null ? response : ""));
    }

    @PostMapping("/smart-home-action")
    public ResponseEntity<?> dispatchSmartHomeAction(@RequestBody Map<String, Object> body) {
        String deviceId = body.get("deviceId") != null ? String.valueOf(body.get("deviceId")) : null;
        String action = body.get("action") != null ? String.valueOf(body.get("action")) : null;
        Object payload = body.get("payload");
        String userId = body.get("userId") != null ? String.valueOf(body.get("userId")) : null;

        if (deviceId == null || deviceId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "deviceId is required"));
        }
        if (action == null || action.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "action is required"));
        }

        log.info("Routing internal voice smart-home action: deviceId={}, action={}, userId={}",
                deviceId, action, userId);
        smartHomeActionGateway.execute(userId, deviceId, action, payload);

        return ResponseEntity.ok(Map.of(
                "status", "dispatched",
                "deviceId", deviceId,
                "action", action,
                "userId", userId != null ? userId : ""));
    }
}
