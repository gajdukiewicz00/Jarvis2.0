package org.jarvis.orchestrator.client;

import org.jarvis.orchestrator.config.ServiceAuthFeignConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * Client for API Gateway internal PC Control endpoint.
 * Sends PC actions to be relayed via WebSocket to Desktop clients.
 */
@FeignClient(
        name = "api-gateway-pc",
        url = "${api-gateway.url:${API_GATEWAY_URL:http://api-gateway:8080}}",
        configuration = ServiceAuthFeignConfig.class)
public interface ApiGatewayPcClient {

    /**
     * Send PC action to connected Desktop clients via WebSocket.
     */
    @PostMapping("/internal/pc-control/action")
    Map<String, Object> sendPcAction(@RequestBody PcActionRequest request);

    /**
     * Get status of connected Desktop clients.
     */
    @GetMapping("/internal/pc-control/status")
    Map<String, Object> getStatus();

    record PcActionRequest(String action, Map<String, Object> params, String userId) {
    }
}
