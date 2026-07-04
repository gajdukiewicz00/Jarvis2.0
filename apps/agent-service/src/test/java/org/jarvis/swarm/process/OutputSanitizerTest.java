package org.jarvis.swarm.process;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OutputSanitizerTest {

    private final OutputSanitizer sanitizer = new OutputSanitizer();

    @Test
    void redactsKeyValueSecrets() {
        String out = sanitizer.sanitize("login ok\napi_key=ABC123XYZ\npassword: hunter2");
        assertThat(out).doesNotContain("ABC123XYZ");
        assertThat(out).doesNotContain("hunter2");
        assertThat(out).contains("[redacted-secret]");
    }

    @Test
    void detectsSecretPresence() {
        assertThat(sanitizer.containsSecret("token=abcdef")).isTrue();
        assertThat(sanitizer.containsSecret("nothing to see here")).isFalse();
    }

    @Test
    void truncatesVeryLongOutput() {
        String big = "x".repeat(10_000);
        assertThat(sanitizer.sanitize(big)).hasSizeLessThan(10_000).contains("[truncated]");
    }

    @Test
    void handlesNullAndBlank() {
        assertThat(sanitizer.sanitize(null)).isEmpty();
        assertThat(sanitizer.sanitize("   ")).isEmpty();
    }
}
