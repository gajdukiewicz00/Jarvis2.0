package org.jarvis.apigateway.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

/**
 * Feign client to communicate with security-service for JWT validation
 */
@FeignClient(name = "security-service-client", url = "${services.security.url:http://localhost:8088}")
public interface SecurityServiceClient {

    @PostMapping("/api/v1/auth/validate")
    Map<String, Object> validateToken(@RequestBody Map<String, String> request);
}
