package org.jarvis.media.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Extracts user identity from gateway-injected headers (set by api-gateway's JWT
 * filter and propagated by {@code GatewayAuthFilter}). Mirrors the convention used
 * by the other Jarvis services.
 */
public final class UserContext {

    private static final String HEADER_USER_ID = "X-User-Id";
    private static final String HEADER_USERNAME = "X-Username";
    private static final String HEADER_USER_ROLE = "X-User-Role";

    private UserContext() {
    }

    public static String getUserId(HttpServletRequest request) {
        return request.getHeader(HEADER_USER_ID);
    }

    public static String getUsername(HttpServletRequest request) {
        return request.getHeader(HEADER_USERNAME);
    }

    public static String getUserRole(HttpServletRequest request) {
        return request.getHeader(HEADER_USER_ROLE);
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
