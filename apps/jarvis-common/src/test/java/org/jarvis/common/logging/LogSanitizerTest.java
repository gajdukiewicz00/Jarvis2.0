package org.jarvis.common.logging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogSanitizerTest {

    @Test
    void sanitizeIdReturnsShortHashWhenPiiEnabled() {
        LogSanitizer sanitizer = new LogSanitizer(true, false, 32);

        String sanitized = sanitizer.sanitizeId("user-123");

        assertNotEquals("user-123", sanitized);
        assertTrue(sanitized.matches("[0-9a-f]{12}"));
    }

    @Test
    void sanitizeTextHidesRawQueryByDefault() {
        LogSanitizer sanitizer = new LogSanitizer(true, false, 32);

        String sanitized = sanitizer.sanitizeText("my private question");

        assertTrue(sanitized.contains("len=19"));
        assertTrue(sanitized.contains("hash="));
        assertFalse(sanitized.contains("my private question"));
        assertFalse(sanitized.contains("snippet="));
    }

    @Test
    void sanitizeTextAddsSnippetWhenEnabled() {
        LogSanitizer sanitizer = new LogSanitizer(true, true, 5);

        String sanitized = sanitizer.sanitizeText("hello world");

        assertTrue(sanitized.contains("len=11"));
        assertTrue(sanitized.contains("hash="));
        assertTrue(sanitized.contains("snippet=\"hello...\""));
    }

    @Test
    void sanitizerCanBeDisabledForLocalDebug() {
        LogSanitizer sanitizer = new LogSanitizer(false, false, 32);

        assertEquals("user-123", sanitizer.sanitizeId("user-123"));
        assertEquals("raw query", sanitizer.sanitizeText("raw query"));
    }
}
