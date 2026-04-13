package org.jarvis.apigateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.jarvis.apigateway.capability.GatewayCapabilityService;
import org.jarvis.apigateway.proxy.DownstreamProxyService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/tools")
@RequiredArgsConstructor
public class ToolProxyController {

    private final DownstreamProxyService downstreamProxyService;
    private final GatewayCapabilityService gatewayCapabilityService;

    @Value("${services.planner.url}")
    private String plannerServiceUrl;

    @Value("${services.life-tracker.url}")
    private String lifeTrackerUrl;

    @Value("${services.memory.url}")
    private String memoryServiceUrl;

    @RequestMapping(value = {"", "/**"},
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE})
    public ResponseEntity<byte[]> proxy(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.startsWith("/api/v1/tools/todo")) {
            return downstreamProxyService.forward(request, "planner-service", plannerServiceUrl);
        }
        if (path.startsWith("/api/v1/tools/calendar") || path.startsWith("/api/v1/tools/finance")) {
            return downstreamProxyService.forward(request, "life-tracker", lifeTrackerUrl);
        }
        if (path.startsWith("/api/v1/tools/memory")) {
            gatewayCapabilityService.requireMemorySupport("memory-tooling");
            return downstreamProxyService.forward(request, "memory-service", memoryServiceUrl);
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unsupported tool route");
    }
}
