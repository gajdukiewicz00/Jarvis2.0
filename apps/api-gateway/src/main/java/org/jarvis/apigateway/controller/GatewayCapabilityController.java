package org.jarvis.apigateway.controller;

import lombok.RequiredArgsConstructor;
import org.jarvis.apigateway.capability.GatewayCapabilityService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/capabilities")
@RequiredArgsConstructor
public class GatewayCapabilityController {

    private final GatewayCapabilityService gatewayCapabilityService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> describe() {
        return ResponseEntity.ok(gatewayCapabilityService.describeCapabilities());
    }
}
