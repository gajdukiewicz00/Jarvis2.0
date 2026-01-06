package org.jarvis.apigateway.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.apigateway.client.OrchestratorClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/orchestrator")
@RequiredArgsConstructor
public class OrchestratorProxyController {

    private final OrchestratorClient orchestratorClient;

    @PostMapping("/execute")
    public ResponseEntity<String> execute(@RequestBody Map<String, String> request) {
        log.info("Proxying POST /api/v1/orchestrator/execute: {}", request.get("text"));
        return orchestratorClient.execute(request);
    }
}
