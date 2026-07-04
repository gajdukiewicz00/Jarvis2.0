package org.jarvis.swarm.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Extracts user identity from gateway-injected headers (set by api-gateway's JWT filter
 * and propagated by {@code GatewayAuthFilter}). Mirrors the other Jarvis services.
 */
public final class UserContext {

    private static final String HEADER_USER_ID = "X-User-Id";

    private UserContext() {
    }

    public static String getUserId(HttpServletRequest request) {
        return request.getHeader(HEADER_USER_ID);
    }

    /** Resolve the caller's user id or throw {@link UnauthenticatedException} when absent. */
    public static String requireUserId(HttpServletRequest request) {
        String userId = getUserId(request);
        if (userId == null || userId.isBlank()) {
            throw new UnauthenticatedException();
        }
        return userId;
    }
}
