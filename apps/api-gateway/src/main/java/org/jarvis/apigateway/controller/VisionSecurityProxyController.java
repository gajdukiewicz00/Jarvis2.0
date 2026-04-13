package org.jarvis.apigateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.jarvis.apigateway.capability.GatewayCapabilityService;
import org.jarvis.apigateway.proxy.DownstreamProxyService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/vision-security")
@RequiredArgsConstructor
public class VisionSecurityProxyController {

    private final DownstreamProxyService downstreamProxyService;
    private final GatewayCapabilityService gatewayCapabilityService;

    @Value("${services.vision-security.url}")
    private String visionSecurityUrl;

    @RequestMapping(value = {"", "/**"},
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE})
    public ResponseEntity<byte[]> proxy(HttpServletRequest request) {
        gatewayCapabilityService.requireVisionSecuritySupport("vision-security");
        return downstreamProxyService.forward(request, "vision-security-service", visionSecurityUrl);
    }
}
