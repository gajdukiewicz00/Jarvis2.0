package org.jarvis.apigateway.security;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks JWT / bearer-token values before they can reach a log line or a
 * client-facing error response body.
 *
 * <p>Mirrors the {@code org.jarvis.common.logging.LogSanitizer} pattern
 * (never write a raw sensitive value to a log or response) but is scoped to
 * token-shaped values specifically: instead of a one-way hash it keeps a
 * short, non-replayable prefix plus the original length, e.g.
 * {@code "eyJhbG…(len=181)"} — enough to correlate a log line or support
 * ticket without ever exposing enough of the token to reuse it.</p>
 *
 * <p>Duplicated rather than added to {@code jarvis-common} because this
 * hardening pass is scoped to api-gateway and security-service only.</p>
 */
public final class TokenMaskingUtil {

    private static final int PREFIX_LENGTH = 6;
    private static final String EMPTY_PLACEHOLDER = "<empty>";
    private static final String BEARER_PREFIX = "Bearer ";

    // JWT compact serialization: base64url(header).base64url(payload).base64url(signature).
    // Each segment must be reasonably long so short, incidental dotted strings (version
    // numbers, hostnames, etc.) are not falsely treated as tokens.
    private static final Pattern JWT_PATTERN =
            Pattern.compile("[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}");

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "token", "accesstoken", "refreshtoken", "authorization", "bearer", "jwt", "idtoken");

    private TokenMaskingUtil() {
    }

    /**
     * Masks a single raw token/header value down to a short prefix plus its
     * original length. Never returns the full value.
     */
    public static String mask(String value) {
        if (value == null || value.isBlank()) {
            return EMPTY_PLACEHOLDER;
        }
        String stripped = stripBearerPrefix(value);
        int prefixLen = Math.min(PREFIX_LENGTH, stripped.length());
        return stripped.substring(0, prefixLen) + "…(len=" + stripped.length() + ")";
    }

    /**
     * Scans free-form text (e.g. an exception message or an upstream error
     * body rendered as a string) and masks any JWT-shaped substring found,
     * leaving the rest of the text untouched. Text with no token-shaped
     * substring is returned unchanged.
     */
    public static String maskTokensInText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher matcher = JWT_PATTERN.matcher(text);
        StringBuilder result = new StringBuilder();
        int lastEnd = 0;
        boolean matched = false;
        while (matcher.find()) {
            matched = true;
            result.append(text, lastEnd, matcher.start());
            result.append(mask(matcher.group()));
            lastEnd = matcher.end();
        }
        if (!matched) {
            return text;
        }
        result.append(text.substring(lastEnd));
        return result.toString();
    }

    /**
     * Recursively walks a parsed JSON-like structure (as produced by e.g.
     * {@code ObjectMapper.readValue(..., Object.class)}: nested
     * {@link Map}/{@link List}/{@link String}/primitives) and masks any
     * value under a token-shaped key, plus any JWT-shaped substring found in
     * a plain string leaf. Non-string/non-container values (numbers,
     * booleans, null) pass through unchanged.
     */
    public static Object maskTokensInStructure(Object node) {
        if (node instanceof Map<?, ?> map) {
            Map<Object, Object> masked = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object key = entry.getKey();
                Object value = entry.getValue();
                if (key instanceof String stringKey && isSensitiveKey(stringKey) && value instanceof String stringValue) {
                    masked.put(key, mask(stringValue));
                } else {
                    masked.put(key, maskTokensInStructure(value));
                }
            }
            return masked;
        }
        if (node instanceof List<?> list) {
            List<Object> masked = new ArrayList<>(list.size());
            for (Object item : list) {
                masked.add(maskTokensInStructure(item));
            }
            return masked;
        }
        if (node instanceof String stringValue) {
            return maskTokensInText(stringValue);
        }
        return node;
    }

    private static String stripBearerPrefix(String value) {
        return value.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())
                ? value.substring(BEARER_PREFIX.length()).trim()
                : value;
    }

    private static boolean isSensitiveKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return SENSITIVE_KEYS.contains(normalized);
    }
}
