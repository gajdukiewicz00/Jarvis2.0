package org.jarvis.swarm.process;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort pass/fail summary extraction from common test-runner output formats
 * (Maven Surefire, Gradle, npm/Jest, pytest). Runs AFTER {@link OutputSanitizer} has
 * already redacted/truncated the text, so this never sees raw, unredacted output.
 * Returns an empty string when no recognizable summary line is found — callers fall
 * back to the exit code alone in that case.
 */
public final class TestOutputSummarizer {

    // Maven Surefire: "Tests run: 5, Failures: 1, Errors: 0, Skipped: 2"
    private static final Pattern SUREFIRE = Pattern.compile(
            "Tests run:\\s*(\\d+),\\s*Failures:\\s*(\\d+),\\s*Errors:\\s*(\\d+),\\s*Skipped:\\s*(\\d+)");

    // npm/Jest: "Tests:       1 failed, 2 skipped, 4 passed, 7 total"
    private static final Pattern JEST = Pattern.compile(
            "Tests:\\s*(?:(\\d+) failed,\\s*)?(?:(\\d+) skipped,\\s*)?(\\d+) passed,\\s*(\\d+) total");

    // Gradle: "7 tests completed, 1 failed"
    private static final Pattern GRADLE = Pattern.compile("(\\d+) tests? completed(?:,\\s*(\\d+) failed)?");

    // pytest: "5 passed, 1 failed, 2 skipped in 0.12s" (subset/order varies)
    private static final Pattern PYTEST = Pattern.compile(
            "(?:(\\d+) passed)?(?:,\\s*)?(?:(\\d+) failed)?(?:,\\s*)?(?:(\\d+) skipped)?(?:,\\s*)?"
                    + "(?:(\\d+) error(?:s)?)?\\s+in\\s+[\\d.]+s");

    private TestOutputSummarizer() {
    }

    public static String summarize(String output) {
        if (output == null || output.isBlank()) {
            return "";
        }
        Matcher surefire = SUREFIRE.matcher(output);
        if (surefire.find()) {
            return "Tests run: " + surefire.group(1) + ", Failures: " + surefire.group(2)
                    + ", Errors: " + surefire.group(3) + ", Skipped: " + surefire.group(4);
        }
        Matcher jest = JEST.matcher(output);
        if (jest.find()) {
            return "Tests: " + zeroIfNull(jest.group(1)) + " failed, " + jest.group(3) + " passed, "
                    + zeroIfNull(jest.group(2)) + " skipped, " + jest.group(4) + " total";
        }
        Matcher gradle = GRADLE.matcher(output);
        if (gradle.find()) {
            return gradle.group(1) + " tests completed, " + zeroIfNull(gradle.group(2)) + " failed";
        }
        Matcher pytest = PYTEST.matcher(output);
        if (pytest.find() && (pytest.group(1) != null || pytest.group(2) != null)) {
            return zeroIfNull(pytest.group(1)) + " passed, " + zeroIfNull(pytest.group(2)) + " failed, "
                    + zeroIfNull(pytest.group(3)) + " skipped, " + zeroIfNull(pytest.group(4)) + " errors";
        }
        return "";
    }

    private static String zeroIfNull(String value) {
        return value == null ? "0" : value;
    }
}
