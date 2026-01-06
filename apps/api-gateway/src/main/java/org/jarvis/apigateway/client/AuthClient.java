package org.jarvis.apigateway.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.Map;

/**
 * Feign client to communicate with security-service for authentication
 */
@FeignClient(name = "auth-service-client", url = "${services.security.url:http://localhost:8088}")
public interface AuthClient {

    @PostMapping("/auth/register")
    ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, Object> request);

    @PostMapping("/auth/login")
    ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, Object> request);

    @PostMapping("/auth/refresh")
    ResponseEntity<Map<String, Object>> refresh(@RequestBody Map<String, Object> request);

    @GetMapping("/auth/me")
    ResponseEntity<Map<String, Object>> me(@RequestHeader(value = "Authorization", required = false) String authorization);
}
