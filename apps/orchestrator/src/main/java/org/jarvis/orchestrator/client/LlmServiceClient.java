package org.jarvis.orchestrator.client;

import org.jarvis.orchestrator.dto.LlmChatRequest;
import org.jarvis.orchestrator.dto.LlmChatResponse;
import org.jarvis.orchestrator.config.ServiceAuthFeignConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(
        name = "llm-service",
        url = "${jarvis.llm.url:http://localhost:8091}",
        configuration = ServiceAuthFeignConfig.class)
public interface LlmServiceClient {

    @PostMapping("/api/v1/llm/chat")
    LlmChatResponse chat(
            @RequestBody LlmChatRequest request,
            @RequestHeader("X-Correlation-ID") String correlationId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Model-Profile", required = false) String modelProfile);
}
