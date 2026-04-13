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
@RequestMapping("/api/v1/pc")
@RequiredArgsConstructor
public class PcControlProxyController {

    private final DownstreamProxyService downstreamProxyService;
    private final GatewayCapabilityService gatewayCapabilityService;

    @Value("${services.pc-control.url}")
    private String pcControlUrl;

    @RequestMapping(value = {"", "/**"},
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE})
    public ResponseEntity<byte[]> proxy(HttpServletRequest request) {
        gatewayCapabilityService.requireDirectPcControlSupport("pc-control");
        return downstreamProxyService.forward(request, "pc-control", pcControlUrl);
    }
}
