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
 * Proxies the user-profile service, which exposes two prefixes:
 * {@code /api/v1/user-profile/**} (goals, habits, priorities, planning-context)
 * and {@code /api/v1/profile/preferences/**}. Both were unrouted at the gateway
 * (404) even though the pod is healthy, leaving the whole service orphaned.
 */
@RestController
@RequiredArgsConstructor
public class UserProfileProxyController {

    private final DownstreamProxyService downstreamProxyService;

    @Value("${services.user-profile.url}")
    private String userProfileUrl;

    @RequestMapping(
            value = {
                    "/api/v1/user-profile", "/api/v1/user-profile/**",
                    "/api/v1/profile", "/api/v1/profile/**"
            },
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE})
    public ResponseEntity<byte[]> proxy(HttpServletRequest request) {
        return downstreamProxyService.forward(request, "user-profile", userProfileUrl);
    }
}
