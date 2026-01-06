package org.jarvis.apigateway.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@FeignClient(name = "orchestrator", url = "${services.orchestrator.url:http://localhost:8083}")
public interface OrchestratorClient {

    @PostMapping("/api/v1/orchestrator/execute")
    ResponseEntity<String> execute(@RequestBody Map<String, String> request);
}
