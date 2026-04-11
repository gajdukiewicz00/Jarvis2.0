package org.jarvis.apigateway.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "llm-service", url = "${services.llm.url:http://localhost:8091}")
public interface LlmServiceClient {

    @GetMapping("/api/v1/llm/health")
    ResponseEntity<String> health();

    @GetMapping("/api/v1/llm/runtime")
    ResponseEntity<String> runtime();

    @PostMapping(value = "/api/v1/llm/chat",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> chat(@RequestBody String body);

    @PostMapping(value = "/api/v1/llm/dialog",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> dialog(@RequestBody String body);

    @PostMapping(value = "/api/v1/llm/orchestrate",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> orchestrate(@RequestBody String body);

    @DeleteMapping("/api/v1/llm/session/{sessionId}")
    ResponseEntity<Void> clearSession(@PathVariable("sessionId") String sessionId);
}
