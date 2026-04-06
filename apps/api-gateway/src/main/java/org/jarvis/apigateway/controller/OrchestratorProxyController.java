package org.jarvis.apigateway.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.apigateway.client.OrchestratorClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/orchestrator")
@RequiredArgsConstructor
public class OrchestratorProxyController {

    private final OrchestratorClient orchestratorClient;
    @Value("${services.orchestrator.url}")
    private String orchestratorUrl;

    @PostMapping("/execute")
    public ResponseEntity<String> execute(
            @RequestHeader(value = "X-Smoke-Run-Id", required = false) String smokeRunId,
            @RequestBody Map<String, String> request) {
        log.info("Proxying POST /api/v1/orchestrator/execute to {}: text={}, intent={}, smokeRunId={}",
                orchestratorUrl,
                request.get("text"),
                request.get("intent"),
                smokeRunId != null ? smokeRunId : "none");
        return orchestratorClient.execute(request);
    }
}
