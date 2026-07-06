package org.jarvis.common.logging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogSanitizerTest {

    @Test
    void sanitizeIdReturnsShortHashByDefaultWhenPiiDisabled() {
        // piiEnabled=false is the real-world default (LOG_PII_ENABLED unset).
        LogSanitizer sanitizer = new LogSanitizer(false, false, 32);

        String sanitized = sanitizer.sanitizeId("user-123");

        assertNotEquals("user-123", sanitized);
        assertTrue(sanitized.matches("[0-9a-f]{12}"));
    }

    @Test
    void sanitizeTextHidesRawQueryByDefaultWhenPiiDisabled() {
        LogSanitizer sanitizer = new LogSanitizer(false, false, 32);

        String sanitized = sanitizer.sanitizeText("my private question");

        assertTrue(sanitized.contains("len=19"));
        assertTrue(sanitized.contains("hash="));
        assertFalse(sanitized.contains("my private question"));
        assertFalse(sanitized.contains("snippet="));
    }

    @Test
    void sanitizeTextAddsSnippetWhenEnabledAndPiiDisabled() {
        LogSanitizer sanitizer = new LogSanitizer(false, true, 5);

        String sanitized = sanitizer.sanitizeText("hello world");

        assertTrue(sanitized.contains("len=11"));
        assertTrue(sanitized.contains("hash="));
        assertTrue(sanitized.contains("snippet=\"hello...\""));
    }

    @Test
    void sanitizerAllowsRawValuesOnlyWhenPiiExplicitlyEnabled() {
        LogSanitizer sanitizer = new LogSanitizer(true, false, 32);

        assertEquals("user-123", sanitizer.sanitizeId("user-123"));
        assertEquals("raw query", sanitizer.sanitizeText("raw query"));
    }

    @Test
    void sanitizeIdReturnsEmptyMarkerForNullOrBlankRegardlessOfPiiFlag() {
        LogSanitizer piiEnabled = new LogSanitizer(true, false, 32);
        LogSanitizer piiDisabled = new LogSanitizer(false, false, 32);

        assertEquals("<empty>", piiEnabled.sanitizeId(null));
        assertEquals("<empty>", piiEnabled.sanitizeId("   "));
        assertEquals("<empty>", piiDisabled.sanitizeId(""));
    }

    @Test
    void sanitizeTextReturnsNullMarkerForNullTextEvenWhenPiiEnabled() {
        LogSanitizer piiEnabled = new LogSanitizer(true, false, 32);
        LogSanitizer piiDisabled = new LogSanitizer(false, false, 32);

        assertEquals("len=0, hash=<null>", piiEnabled.sanitizeText(null));
        assertEquals("len=0, hash=<null>", piiDisabled.sanitizeText(null));
    }

    @Test
    void snippetNormalizesWhitespaceAndQuotesAndCanBeEmpty() {
        LogSanitizer sanitizer = new LogSanitizer(false, true, 32);

        String withInternalWhitespace = sanitizer.sanitizeText("hello\n\tworld  \"quoted\"");
        assertTrue(withInternalWhitespace.contains("snippet=\"hello world 'quoted'\""));

        String allWhitespace = sanitizer.sanitizeText("   \t  ");
        assertTrue(allWhitespace.contains("snippet=\"\""));
    }

    @Test
    void negativeSnippetMaxLengthIsClampedToZero() {
        LogSanitizer sanitizer = new LogSanitizer(false, true, -5);

        String sanitized = sanitizer.sanitizeText("hello");

        assertTrue(sanitized.contains("snippet=\"...\""));
    }

    @Test
    void defaultConfigurationRedactsUserIdSessionIdAndRequestText() {
        // Regression test for the critical PII-leak finding: with the real
        // production default (LOG_PII_ENABLED unset -> piiEnabled=false),
        // sanitizeId/sanitizeText must NEVER return raw PII.
        LogSanitizer defaultSanitizer = new LogSanitizer(false, false, 32);

        String sanitizedUserId = defaultSanitizer.sanitizeId("user-42");
        String sanitizedSessionId = defaultSanitizer.sanitizeId("session-abc-999");
        String sanitizedText = defaultSanitizer.sanitizeText("my SSN is 123-45-6789");

        assertNotEquals("user-42", sanitizedUserId);
        assertNotEquals("session-abc-999", sanitizedSessionId);
        assertFalse(sanitizedText.contains("123-45-6789"));
        assertTrue(sanitizedUserId.matches("[0-9a-f]{12}"));
        assertTrue(sanitizedSessionId.matches("[0-9a-f]{12}"));
    }

    @Test
    void explicitlyEnablingPiiLoggingPassesThroughRawValues() {
        LogSanitizer optedInSanitizer = new LogSanitizer(true, false, 32);

        assertEquals("user-42", optedInSanitizer.sanitizeId("user-42"));
        assertEquals("my SSN is 123-45-6789", optedInSanitizer.sanitizeText("my SSN is 123-45-6789"));
    }
}
