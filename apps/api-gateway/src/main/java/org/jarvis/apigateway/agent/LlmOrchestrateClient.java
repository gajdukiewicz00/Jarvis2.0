package org.jarvis.apigateway.agent;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Calls llm-service to PLAN tool calls. Auth (X-Service-Token) is attached by
 * the gateway-wide Feign interceptor in {@code FeignAuthConfig}.
 */
@FeignClient(name = "llm-orchestrate", url = "${services.llm.url}")
public interface LlmOrchestrateClient {

    @PostMapping("/api/v1/llm/orchestrate")
    OrchestrateResponse orchestrate(@RequestBody OrchestrateRequest request);
}
