package org.jarvis.orchestrator.client;

import org.jarvis.orchestrator.config.ServiceAuthFeignConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.Map;

/**
 * Reads the user's planner focus/day through the API Gateway's planner proxy
 * ({@code /api/v1/planner/**}). Only the api-gateway can reach planner-service directly
 * (NetworkPolicy), so voice-gateway routes planner reads through the orchestrator, which is
 * allowed to call the gateway. {@link ServiceAuthFeignConfig} attaches the SVC_INTERNAL
 * service token; X-User-Id is forwarded so planner-service resolves the right user's tasks.
 */
@FeignClient(
        name = "api-gateway-planner",
        url = "${api-gateway.url:${API_GATEWAY_URL:http://api-gateway:8080}}",
        configuration = ServiceAuthFeignConfig.class)
public interface ApiGatewayPlannerClient {

    @GetMapping("/api/v1/planner/focus")
    Map<String, Object> getFocus(@RequestHeader("X-User-Id") String userId);

    @GetMapping("/api/v1/planner/daily")
    Map<String, Object> getDaily(@RequestHeader("X-User-Id") String userId);
}
