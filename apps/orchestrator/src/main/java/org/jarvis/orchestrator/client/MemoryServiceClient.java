package org.jarvis.orchestrator.client;

import org.jarvis.orchestrator.config.ServiceAuthFeignConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.Map;

/** Thin client for memory-service search/ingest. Loose Map shapes keep the
 *  orchestrator decoupled from memory-service DTO versions. */
@FeignClient(
        name = "memory-service",
        url = "${jarvis.memory.url:http://localhost:8093}",
        configuration = ServiceAuthFeignConfig.class)
public interface MemoryServiceClient {

    @PostMapping("/memory/search")
    Map<String, Object> search(@RequestBody Map<String, Object> request,
                               @RequestHeader("X-Correlation-ID") String correlationId);

    @PostMapping("/memory/ingest")
    Map<String, Object> ingest(@RequestBody Map<String, Object> request,
                               @RequestHeader("X-Correlation-ID") String correlationId);
}
