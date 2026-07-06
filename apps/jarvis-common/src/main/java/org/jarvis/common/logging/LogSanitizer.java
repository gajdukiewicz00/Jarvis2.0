package org.jarvis.common.logging;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Sanitizes potentially sensitive values before writing them to logs.
 */
public final class LogSanitizer {

    private static final int HASH_PREFIX_BYTES = 6;
    private static final String NULL_HASH = "<null>";
    private static final String EMPTY_VALUE = "<empty>";

    private final boolean piiEnabled;
    private final boolean allowQuerySnippet;
    private final int querySnippetMaxLength;

    public LogSanitizer(boolean piiEnabled, boolean allowQuerySnippet, int querySnippetMaxLength) {
        this.piiEnabled = piiEnabled;
        this.allowQuerySnippet = allowQuerySnippet;
        this.querySnippetMaxLength = Math.max(0, querySnippetMaxLength);
    }

    /**
     * Sanitizes user/session identifiers.
     * Redacts to a short stable hash unless raw PII logging has been
     * explicitly enabled (piiEnabled=true). The safe default (piiEnabled=false,
     * i.e. LOG_PII_ENABLED unset) always redacts.
     */
    public String sanitizeId(String id) {
        if (id == null || id.isBlank()) {
            return EMPTY_VALUE;
        }
        if (piiEnabled) {
            return id;
        }
        return shortHash(id);
    }

    /**
     * Sanitizes free-form user text.
     * By default does not reveal text, only length and hash, unless raw PII
     * logging has been explicitly enabled (piiEnabled=true).
     */
    public String sanitizeText(String text) {
        if (text == null) {
            return "len=0, hash=" + NULL_HASH;
        }
        if (piiEnabled) {
            return text;
        }

        String hash = shortHash(text);
        if (!allowQuerySnippet) {
            return "len=" + text.length() + ", hash=" + hash;
        }

        String snippet = buildSnippet(text);
        return "len=" + text.length() + ", hash=" + hash + ", snippet=\"" + snippet + "\"";
    }

    private String buildSnippet(String text) {
        String normalized = text.replaceAll("\\s+", " ").trim().replace('"', '\'');
        if (normalized.isEmpty()) {
            return "";
        }
        if (normalized.length() <= querySnippetMaxLength) {
            return normalized;
        }
        return normalized.substring(0, querySnippetMaxLength) + "...";
    }

    private String shortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes, 0, HASH_PREFIX_BYTES);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
