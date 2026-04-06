package org.jarvis.orchestrator.controller;

import org.jarvis.orchestrator.client.PcControlClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.orchestrator.client.SmartHomeClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/internal/orchestrator")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SVC_INTERNAL')")
public class InternalOrchestratorSmartHomeController {

    private final SmartHomeClient smartHomeClient;
    private final PcControlClient pcControlClient;

    @Value("${jarvis.smart-home.url:${SMART_HOME_URL:http://smart-home-service:8086}}")
    private String smartHomeUrl;

    @Value("${jarvis.pc-control.url:${PC_CONTROL_URL:http://pc-control:8084}}")
    private String pcControlUrl;

    @PostMapping("/smart-home-action")
    public ResponseEntity<?> dispatchSmartHomeAction(@RequestBody Map<String, Object> body) {
        String deviceId = body.get("deviceId") != null ? String.valueOf(body.get("deviceId")) : null;
        String action = body.get("action") != null ? String.valueOf(body.get("action")) : null;
        String payload = body.get("payload") != null ? String.valueOf(body.get("payload")) : null;
        String userId = body.get("userId") != null ? String.valueOf(body.get("userId")) : null;

        if (deviceId == null || deviceId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "deviceId is required"));
        }
        if (action == null || action.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "action is required"));
        }

        String scopedUserId = userId != null && !userId.isBlank() ? userId : "local-user";
        log.info("Internal orchestrator smart-home route: userId={}, deviceId={}, action={}, smartHomeUrl={}",
                scopedUserId, deviceId, action, smartHomeUrl);

        SmartHomeClient.ActionResult result = smartHomeClient.executeAction(
                scopedUserId,
                deviceId,
                new SmartHomeClient.ActionRequest(action, payload));

        return ResponseEntity.ok(Map.of(
                "status", "dispatched",
                "userId", scopedUserId,
                "deviceId", deviceId,
                "action", action,
                "success", result.success()));
    }

    @PostMapping("/pc-action")
    public ResponseEntity<?> dispatchPcAction(@RequestBody Map<String, Object> body) {
        String actionType = body.get("actionType") != null ? String.valueOf(body.get("actionType")) : null;
        Object paramsValue = body.get("parameters");
        String userId = body.get("userId") != null ? String.valueOf(body.get("userId")) : null;

        if (actionType == null || actionType.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "actionType is required"));
        }

        Map<String, String> parameters = paramsValue instanceof Map<?, ?> raw
                ? raw.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                        entry -> String.valueOf(entry.getKey()),
                        entry -> entry.getValue() != null ? String.valueOf(entry.getValue()) : "",
                        (left, right) -> right,
                        java.util.LinkedHashMap::new))
                : Map.of();
        String scopedUserId = userId != null && !userId.isBlank() ? userId : "local-user";

        log.info("Internal orchestrator pc-control route: userId={}, actionType={}, parameters={}, pcControlUrl={}",
                scopedUserId, actionType, parameters, pcControlUrl);
        pcControlClient.executeAction(scopedUserId, new PcControlClient.ActionRequest(actionType, parameters));

        return ResponseEntity.ok(Map.of(
                "status", "dispatched",
                "actionType", actionType,
                "userId", scopedUserId));
    }
}
