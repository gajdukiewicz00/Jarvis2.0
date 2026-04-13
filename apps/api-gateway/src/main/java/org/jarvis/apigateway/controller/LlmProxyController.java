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
@RequestMapping("/api/v1/llm")
@RequiredArgsConstructor
public class LlmProxyController {

    private final DownstreamProxyService downstreamProxyService;
    private final GatewayCapabilityService gatewayCapabilityService;

    @Value("${services.llm.url}")
    private String llmServiceUrl;

    @RequestMapping(value = {"", "/**"},
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE})
    public ResponseEntity<byte[]> proxy(HttpServletRequest request) {
        gatewayCapabilityService.requireLlmSupport("llm");
        return downstreamProxyService.forward(request, "llm-service", llmServiceUrl);
    }
}
