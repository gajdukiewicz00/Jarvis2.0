package org.jarvis.common.util;

/**
 * Common string utilities for Jarvis services.
 */
public final class StringUtils {

    private StringUtils() {
        // Utility class
    }

    /**
     * Truncates string to specified length, adding "..." if truncated.
     * 
     * @param s String to truncate
     * @param maxLen Maximum length
     * @return Truncated string or original if shorter than maxLen
     */
    public static String truncate(String s, int maxLen) {
        if (s == null) {
            return "<null>";
        }
        if (maxLen <= 3) {
            return s.length() <= maxLen ? s : s.substring(0, maxLen);
        }
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
    }

    /**
     * Extracts user ID from session ID.
     * Assumes format: "userId-sessionSuffix" or just "userId".
     * 
     * @param sessionId Session ID string
     * @return User ID portion
     */
    public static String extractUserId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return "anonymous";
        }
        int dashIndex = sessionId.indexOf('-');
        if (dashIndex > 0) {
            return sessionId.substring(0, dashIndex);
        }
        return sessionId;
    }

    /**
     * Checks if string is null, empty, or blank.
     */
    public static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * Checks if string is not null, not empty, and not blank.
     */
    public static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * Returns default value if string is blank.
     */
    public static String defaultIfBlank(String s, String defaultValue) {
        return isBlank(s) ? defaultValue : s;
    }

    /**
     * Masks sensitive data, showing only first and last N characters.
     * Useful for logging tokens, passwords, etc.
     * 
     * @param s String to mask
     * @param visibleChars Number of characters to show at start and end
     * @return Masked string like "abc***xyz"
     */
    public static String mask(String s, int visibleChars) {
        if (s == null) {
            return "<null>";
        }
        if (s.length() <= visibleChars * 2) {
            return "***";
        }
        return s.substring(0, visibleChars) + "***" + s.substring(s.length() - visibleChars);
    }
}

