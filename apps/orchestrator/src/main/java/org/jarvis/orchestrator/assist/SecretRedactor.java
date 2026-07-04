package org.jarvis.orchestrator.assist;

import java.util.regex.Pattern;

/** Redacts secret-looking text so it is never spoken, logged, or returned. */
public final class SecretRedactor {

    private static final Pattern KV =
            Pattern.compile("(?i)\\b(key|token|secret|password|api[-_]?key|access[-_]?key)\\b\\s*[=:]\\s*\\S+");
    private static final Pattern LONG_B64 =
            Pattern.compile("\\b[A-Za-z0-9+/]{32,}={0,2}\\b");
    private static final Pattern PRIVKEY =
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----");

    private SecretRedactor() {}

    public static String redact(String s) {
        if (s == null) return null;
        s = PRIVKEY.matcher(s).replaceAll("<redacted-private-key>");
        s = KV.matcher(s).replaceAll("$1=<redacted>");
        s = LONG_B64.matcher(s).replaceAll("<redacted>");
        return s;
    }
}
