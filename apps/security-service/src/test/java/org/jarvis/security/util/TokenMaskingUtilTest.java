package org.jarvis.security.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TokenMaskingUtil} — asserts that a full token value
 * is never present in the masked output, only a short prefix plus length.
 */
class TokenMaskingUtilTest {

    // Deliberately fake but JWT-shaped (header.payload.signature, base64url segments).
    private static final String FAKE_JWT =
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U";

    @Test
    void maskReturnsEmptyPlaceholderForNullOrBlank() {
        assertThat(TokenMaskingUtil.mask(null)).isEqualTo("<empty>");
        assertThat(TokenMaskingUtil.mask("")).isEqualTo("<empty>");
        assertThat(TokenMaskingUtil.mask("   ")).isEqualTo("<empty>");
    }

    @Test
    void maskKeepsOnlyShortPrefixAndLength() {
        String masked = TokenMaskingUtil.mask(FAKE_JWT);

        assertThat(masked).doesNotContain(FAKE_JWT);
        assertThat(masked).startsWith(FAKE_JWT.substring(0, 6));
        assertThat(masked).contains("len=" + FAKE_JWT.length());
    }

    @Test
    void maskStripsBearerPrefixBeforeMasking() {
        String masked = TokenMaskingUtil.mask("Bearer " + FAKE_JWT);

        assertThat(masked).doesNotContain(FAKE_JWT);
        assertThat(masked).contains("len=" + FAKE_JWT.length());
    }

    @Test
    void maskTokensInTextReturnsPlainTextUnchanged() {
        String text = "Invalid JWT token";

        assertThat(TokenMaskingUtil.maskTokensInText(text)).isEqualTo(text);
    }

    @Test
    void maskTokensInTextHandlesNullAndEmpty() {
        assertThat(TokenMaskingUtil.maskTokensInText(null)).isNull();
        assertThat(TokenMaskingUtil.maskTokensInText("")).isEmpty();
    }

    @Test
    void maskTokensInTextMasksEmbeddedJwtButKeepsSurroundingText() {
        String text = "unexpected error while validating " + FAKE_JWT + " for refresh";

        String masked = TokenMaskingUtil.maskTokensInText(text);

        assertThat(masked).doesNotContain(FAKE_JWT);
        assertThat(masked).startsWith("unexpected error while validating ");
        assertThat(masked).endsWith(" for refresh");
        assertThat(masked).contains("len=" + FAKE_JWT.length());
    }

    @Test
    void maskTokensInStructureMasksSensitiveKeysRegardlessOfShape() {
        Map<String, Object> body = Map.of(
                "token", "opaque-session-value-not-jwt-shaped",
                "reason", "TOKEN_REVOKED");

        @SuppressWarnings("unchecked")
        Map<String, Object> masked = (Map<String, Object>) TokenMaskingUtil.maskTokensInStructure(body);

        assertThat(masked.get("token").toString()).doesNotContain("opaque-session-value-not-jwt-shaped");
        assertThat(masked.get("reason")).isEqualTo("TOKEN_REVOKED");
    }

    @Test
    void maskTokensInStructureMasksJwtShapedStringsNestedInLists() {
        Map<String, Object> body = Map.of("errors", List.of("bad token: " + FAKE_JWT));

        @SuppressWarnings("unchecked")
        Map<String, Object> masked = (Map<String, Object>) TokenMaskingUtil.maskTokensInStructure(body);
        @SuppressWarnings("unchecked")
        List<String> errors = (List<String>) masked.get("errors");

        assertThat(errors.get(0)).doesNotContain(FAKE_JWT);
        assertThat(errors.get(0)).contains("len=" + FAKE_JWT.length());
    }

    @Test
    void maskTokensInStructurePassesThroughNonStringLeaves() {
        Map<String, Object> body = Map.of("status", 401, "valid", false);

        @SuppressWarnings("unchecked")
        Map<String, Object> masked = (Map<String, Object>) TokenMaskingUtil.maskTokensInStructure(body);

        assertThat(masked.get("status")).isEqualTo(401);
        assertThat(masked.get("valid")).isEqualTo(false);
    }

    @Test
    void maskTokensInStructureHandlesNull() {
        assertThat(TokenMaskingUtil.maskTokensInStructure(null)).isNull();
    }
}
