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
 * Proxies {@code /api/v1/life-map/**} to life-tracker. The life-map endpoints
 * (weekly rollup, activity map) live under a different prefix than
 * {@code /api/v1/life/**} ({@link LifeTrackerProxyController}), so without this
 * controller the gateway returns 404 for the weekly summary even though the
 * backend handler exists.
 */
@RestController
@RequestMapping("/api/v1/life-map")
@RequiredArgsConstructor
public class LifeMapProxyController {

    private final DownstreamProxyService downstreamProxyService;

    @Value("${services.life-tracker.url}")
    private String lifeTrackerUrl;

    @RequestMapping(value = {"", "/**"},
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE})
    public ResponseEntity<byte[]> proxy(HttpServletRequest request) {
        return downstreamProxyService.forward(request, "life-tracker", lifeTrackerUrl);
    }
}
