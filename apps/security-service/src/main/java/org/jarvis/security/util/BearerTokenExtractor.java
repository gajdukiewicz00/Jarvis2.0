package org.jarvis.security.util;

import org.jarvis.security.config.GlobalExceptionHandler.AuthenticationException;

/**
 * Shared "Authorization: Bearer &lt;token&gt;" header parsing for controllers
 * that enforce authorization manually (this service has no method-security
 * filter chain - see {@code SecurityConfig}).
 */
public final class BearerTokenExtractor {

    private static final String BEARER_PREFIX = "Bearer ";

    private BearerTokenExtractor() {
    }

    public static String extract(String authHeader) {
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            throw new AuthenticationException("MISSING_TOKEN", "Missing or invalid Authorization header");
        }
        String token = authHeader.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            throw new AuthenticationException("EMPTY_TOKEN", "Token is empty");
        }
        return token;
    }
}
