package org.jarvis.apigateway.security;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TokenMaskingUtil} — asserts that a full token value
 * is never present in the masked output, only a short prefix plus length.
 */
class TokenMaskingUtilTest {

    // Deliberately fake but JWT-shaped (header.payload.signature, base64url segments).
    private static final String FAKE_JWT =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyLTEiLCJyb2xlIjoiT1dORVIifQ.dGhpc2lzbm90YXJlYWxzaWduYXR1cmU";

    @Test
    void maskReturnsEmptyPlaceholderForNullOrBlank() {
        assertEquals("<empty>", TokenMaskingUtil.mask(null));
        assertEquals("<empty>", TokenMaskingUtil.mask(""));
        assertEquals("<empty>", TokenMaskingUtil.mask("   "));
    }

    @Test
    void maskKeepsOnlyShortPrefixAndLength() {
        String masked = TokenMaskingUtil.mask(FAKE_JWT);

        assertFalse(masked.contains(FAKE_JWT), "masked value must not contain the full token");
        assertTrue(masked.startsWith(FAKE_JWT.substring(0, 6)), "masked value should retain a short prefix");
        assertTrue(masked.contains("len=" + FAKE_JWT.length()), "masked value should report the original length");
    }

    @Test
    void maskStripsBearerPrefixBeforeMasking() {
        String masked = TokenMaskingUtil.mask("Bearer " + FAKE_JWT);

        assertFalse(masked.contains(FAKE_JWT));
        assertTrue(masked.contains("len=" + FAKE_JWT.length()), "length should reflect the token only, not 'Bearer '");
    }

    @Test
    void maskTokensInTextReturnsPlainTextUnchanged() {
        String text = "Upstream returned: not found";

        assertEquals(text, TokenMaskingUtil.maskTokensInText(text));
    }

    @Test
    void maskTokensInTextHandlesNullAndEmpty() {
        assertEquals(null, TokenMaskingUtil.maskTokensInText(null));
        assertEquals("", TokenMaskingUtil.maskTokensInText(""));
    }

    @Test
    void maskTokensInTextMasksEmbeddedJwtButKeepsSurroundingText() {
        String text = "refresh failed for token=" + FAKE_JWT + " on attempt 2";

        String masked = TokenMaskingUtil.maskTokensInText(text);

        assertFalse(masked.contains(FAKE_JWT), "the raw token must not survive masking");
        assertTrue(masked.startsWith("refresh failed for token="));
        assertTrue(masked.endsWith(" on attempt 2"));
        assertTrue(masked.contains("len=" + FAKE_JWT.length()));
    }

    @Test
    void maskTokensInStructureMasksSensitiveKeysRegardlessOfShape() {
        Map<String, Object> body = Map.of(
                "accessToken", "opaque-session-value-not-jwt-shaped",
                "message", "login failed");

        @SuppressWarnings("unchecked")
        Map<String, Object> masked = (Map<String, Object>) TokenMaskingUtil.maskTokensInStructure(body);

        assertFalse(masked.get("accessToken").toString().contains("opaque-session-value-not-jwt-shaped"));
        assertEquals("login failed", masked.get("message"));
    }

    @Test
    void maskTokensInStructureMasksJwtShapedStringsNestedInLists() {
        Map<String, Object> body = Map.of(
                "errors", List.of("bad refreshToken: " + FAKE_JWT));

        @SuppressWarnings("unchecked")
        Map<String, Object> masked = (Map<String, Object>) TokenMaskingUtil.maskTokensInStructure(body);
        @SuppressWarnings("unchecked")
        List<String> errors = (List<String>) masked.get("errors");

        assertFalse(errors.get(0).contains(FAKE_JWT));
        assertTrue(errors.get(0).contains("len=" + FAKE_JWT.length()));
    }

    @Test
    void maskTokensInStructurePassesThroughNonStringLeaves() {
        Map<String, Object> body = Map.of("status", 502, "retryable", true);

        @SuppressWarnings("unchecked")
        Map<String, Object> masked = (Map<String, Object>) TokenMaskingUtil.maskTokensInStructure(body);

        assertEquals(502, masked.get("status"));
        assertEquals(true, masked.get("retryable"));
    }

    @Test
    void maskTokensInStructureHandlesNull() {
        assertEquals(null, TokenMaskingUtil.maskTokensInStructure(null));
    }
}
