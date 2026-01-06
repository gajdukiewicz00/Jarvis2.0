package org.jarvis.lifetracker.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Utility class to extract user context from request headers
 * set by api-gateway's JWT filter
 */
public class UserContext {

    private static final String HEADER_USER_ID = "X-User-Id";
    private static final String HEADER_USERNAME = "X-Username";
    private static final String HEADER_USER_ROLE = "X-User-Role";

    public static String getUserId(HttpServletRequest request) {
        return request.getHeader(HEADER_USER_ID);
    }

    public static String getUsername(HttpServletRequest request) {
        return request.getHeader(HEADER_USERNAME);
    }

    public static String getUserRole(HttpServletRequest request) {
        return request.getHeader(HEADER_USER_ROLE);
    }

    public static boolean isAdmin(HttpServletRequest request) {
        return "ADMIN".equalsIgnoreCase(getUserRole(request));
    }

    public static boolean isAuthenticated(HttpServletRequest request) {
        return getUserId(request) != null && !getUserId(request).isEmpty();
    }
}
