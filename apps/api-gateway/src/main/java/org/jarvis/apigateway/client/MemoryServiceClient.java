package org.jarvis.apigateway.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.Map;

@FeignClient(name = "memory-service", url = "${services.memory.url:http://memory-service:8093}")
public interface MemoryServiceClient {

    @PostMapping("/api/v1/tools/memory/search")
    ResponseEntity<Map<String, Object>> searchMemory(
            @RequestHeader("X-User-Id") String userId,
            @RequestBody Map<String, Object> payload);
}
