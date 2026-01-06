package org.jarvis.planner.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Utility class to extract user context from request headers
 * set by api-gateway's JWT filter
 */
public class UserContext {

    private static final String HEADER_USER_ID = "X-User-Id";
    private static final String HEADER_USERNAME = "X-Username";
    private static final String HEADER_USER_ROLE = "X-User-Role";

    /**
     * Get user ID from request headers
     */
    public static String getUserId(HttpServletRequest request) {
        return request.getHeader(HEADER_USER_ID);
    }

    /**
     * Get username from request headers
     */
    public static String getUsername(HttpServletRequest request) {
        return request.getHeader(HEADER_USERNAME);
    }

    /**
     * Get user role from request headers
     */
    public static String getUserRole(HttpServletRequest request) {
        return request.getHeader(HEADER_USER_ROLE);
    }

    /**
     * Check if current user is admin
     */
    public static boolean isAdmin(HttpServletRequest request) {
        String role = getUserRole(request);
        return "ADMIN".equalsIgnoreCase(role);
    }

    /**
     * Check if user is authenticated (has userId header)
     */
    public static boolean isAuthenticated(HttpServletRequest request) {
        return getUserId(request) != null && !getUserId(request).isEmpty();
    }
}
