package org.jarvis.swarm.process;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Redacts secret-like tokens and truncates captured output before it is surfaced in a
 * task result or report. Ensures command output cannot leak credentials even if a tool
 * prints them. Used by the TESTER role and the SECURITY scanner.
 */
@Component
public class OutputSanitizer {

    private static final int MAX_CHARS = 4000;

    private static final List<Pattern> SECRET_PATTERNS = List.of(
            // key=value style secrets
            Pattern.compile("(?i)(api[_-]?key|secret|password|passwd|token|authorization|bearer)\\s*[:=]\\s*\\S+"),
            // bare high-entropy-ish long tokens (jwt-like, aws-like)
            Pattern.compile("(?i)\\beyJ[A-Za-z0-9_\\-]{10,}\\.[A-Za-z0-9_\\-]{10,}\\.[A-Za-z0-9_\\-]{5,}\\b"),
            Pattern.compile("\\bAKIA[0-9A-Z]{12,}\\b"),
            Pattern.compile("(?i)\\b(sk|pk)_(live|test)_[A-Za-z0-9]{10,}\\b"));

    private static final String REDACTED = "[redacted-secret]";

    public String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String out = raw;
        for (Pattern p : SECRET_PATTERNS) {
            out = p.matcher(out).replaceAll(REDACTED);
        }
        if (out.length() > MAX_CHARS) {
            out = out.substring(0, MAX_CHARS) + "\n…[truncated]";
        }
        return out;
    }

    /** True if the text contains a recognizable secret pattern (used by the SECURITY scan). */
    public boolean containsSecret(String text) {
        if (text == null) {
            return false;
        }
        for (Pattern p : SECRET_PATTERNS) {
            if (p.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }
}
