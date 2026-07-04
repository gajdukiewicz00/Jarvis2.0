package org.jarvis.apigateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.jarvis.apigateway.proxy.DownstreamProxyService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Proxies {@code /api/v1/sync/**} to sync-service. Previously absent, so every
 * sync endpoint (device pairing status, sync inbox) returned 404 at the
 * gateway. Authenticated like the other proxy routes — device pairing's
 * unauthenticated handshake continues to use the sync-service NodePort
 * directly (operator-provisioned), this route serves authenticated callers.
 */
@RestController
@RequestMapping("/api/v1/sync")
@RequiredArgsConstructor
public class SyncProxyController {

    private final DownstreamProxyService downstreamProxyService;

    @Value("${services.sync-service.url}")
    private String syncServiceUrl;

    @RequestMapping(value = {"", "/**"},
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE})
    public ResponseEntity<byte[]> proxy(HttpServletRequest request) {
        return downstreamProxyService.forward(request, "sync-service", syncServiceUrl);
    }
}
