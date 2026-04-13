package org.jarvis.apigateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.jarvis.apigateway.proxy.DownstreamProxyService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/auth", "/api/v1/security/auth"})
@RequiredArgsConstructor
public class AuthProxyController {

    private final DownstreamProxyService downstreamProxyService;

    @Value("${services.security.url}")
    private String securityServiceUrl;

    @RequestMapping(value = {"", "/**"},
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE})
    public ResponseEntity<byte[]> proxy(HttpServletRequest request) {
        String gatewayPrefix = request.getRequestURI().startsWith("/api/v1/security/auth")
                ? "/api/v1/security/auth"
                : "/auth";
        return downstreamProxyService.forward(request, "security-service", securityServiceUrl, gatewayPrefix, "/auth");
    }
}
