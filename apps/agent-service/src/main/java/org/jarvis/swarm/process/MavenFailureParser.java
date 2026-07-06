package org.jarvis.swarm.process;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort structured-failure extraction from Maven Surefire text-report output: the
 * {@code Results :} section's {@code Failed tests:} / {@code Tests in error:} bullet
 * lines, each shaped {@code ClassName.methodName:LINE  message} (the format Surefire's
 * console/text reporter emits for both assertion failures and errors — {@code message} is
 * absent for a bare failure with no assertion text). Runs AFTER {@link OutputSanitizer}
 * has already redacted/truncated the text, so this never sees raw, unredacted output.
 * Returns an empty list when no recognizable failure lines are found — callers fall back
 * to {@link TestOutputSummarizer}'s aggregate counts (or the raw output) alone in that
 * case, instead of dumping the whole log.
 */
public final class MavenFailureParser {

    // "Failed tests:" / "Tests in error:" section header (Surefire's own casing; may
    // carry trailing whitespace, stripped before matching).
    private static final Pattern SECTION_HEADER = Pattern.compile("^(Failed tests|Tests in error):$");

    // "  ClassName.methodName:123[ message text]" — FQCN-safe: greedy backtracking finds
    // the LAST '.' before the method name, so both "Foo.bar:12" and "a.b.Foo.bar:12" work.
    private static final Pattern FAILURE_LINE = Pattern.compile(
            "^\\s+([A-Za-z_$][\\w$.]*)\\.(\\w+):(\\d+)(?:\\s+(.*))?$");

    private MavenFailureParser() {
    }

    public static List<TestFailure> parse(String output) {
        List<TestFailure> failures = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return failures;
        }
        boolean inSection = false;
        for (String line : output.split("\n")) {
            if (SECTION_HEADER.matcher(line.strip()).matches()) {
                inSection = true;
                continue;
            }
            if (!inSection) {
                continue;
            }
            if (line.isBlank()) {
                inSection = false;
                continue;
            }
            Matcher m = FAILURE_LINE.matcher(line);
            if (m.matches()) {
                String message = m.group(4) == null ? "" : m.group(4).trim();
                failures.add(new TestFailure(m.group(1), m.group(2), message));
            }
        }
        return failures;
    }
}
