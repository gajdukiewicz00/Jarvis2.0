package org.jarvis.swarm.process;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestOutputSummarizerTest {

    @Test
    void summarizesMavenSurefireOutput() {
        String output = "Running org.jarvis.FooTest\nTests run: 5, Failures: 1, Errors: 0, Skipped: 2\n"
                + "BUILD FAILURE";

        String summary = TestOutputSummarizer.summarize(output);

        assertThat(summary).isEqualTo("Tests run: 5, Failures: 1, Errors: 0, Skipped: 2");
    }

    @Test
    void summarizesJestOutput() {
        String output = "Test Suites: 2 passed, 2 total\nTests:       1 failed, 2 skipped, 4 passed, 7 total\n";

        String summary = TestOutputSummarizer.summarize(output);

        assertThat(summary).isEqualTo("Tests: 1 failed, 4 passed, 2 skipped, 7 total");
    }

    @Test
    void summarizesGradleOutput() {
        String output = "> Task :test\n7 tests completed, 1 failed\nBUILD FAILED";

        String summary = TestOutputSummarizer.summarize(output);

        assertThat(summary).isEqualTo("7 tests completed, 1 failed");
    }

    @Test
    void summarizesPytestOutput() {
        String output = "collected 8 items\n\n========= 5 passed, 1 failed, 2 skipped in 0.12s =========";

        String summary = TestOutputSummarizer.summarize(output);

        assertThat(summary).isEqualTo("5 passed, 1 failed, 2 skipped, 0 errors");
    }

    @Test
    void returnsEmptyStringForUnrecognizedOutput() {
        assertThat(TestOutputSummarizer.summarize("hello world, nothing recognizable here")).isEmpty();
        assertThat(TestOutputSummarizer.summarize("")).isEmpty();
        assertThat(TestOutputSummarizer.summarize(null)).isEmpty();
    }
}
