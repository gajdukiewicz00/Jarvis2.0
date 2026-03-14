package org.jarvis.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StringUtilsTest {

    @Test
    void truncateReturnsNullMarkerForNullValues() {
        assertEquals("<null>", StringUtils.truncate(null, 10));
    }

    @Test
    void truncateAddsEllipsisWhenBudgetAllowsIt() {
        assertEquals("hel...", StringUtils.truncate("hello world", 6));
        assertEquals("hel", StringUtils.truncate("hello world", 3));
    }

    @Test
    void extractUserIdUsesPrefixBeforeDash() {
        assertEquals("user42", StringUtils.extractUserId("user42"));
        assertEquals("alice", StringUtils.extractUserId("alice-session-123"));
        assertEquals("anonymous", StringUtils.extractUserId(" "));
    }

    @Test
    void blankHelpersAndDefaultFallbackWorkTogether() {
        assertTrue(StringUtils.isBlank(" "));
        assertFalse(StringUtils.isNotBlank(" "));
        assertEquals("fallback", StringUtils.defaultIfBlank(" ", "fallback"));
        assertEquals("value", StringUtils.defaultIfBlank("value", "fallback"));
    }

    @Test
    void maskHidesShortValuesAndKeepsEdgesOfLongValues() {
        assertEquals("***", StringUtils.mask("abcd", 2));
        assertEquals("ab***ef", StringUtils.mask("abcdef", 2));
        assertEquals("<null>", StringUtils.mask(null, 2));
    }
}
