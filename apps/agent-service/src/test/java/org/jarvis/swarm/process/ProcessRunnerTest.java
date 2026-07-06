package org.jarvis.swarm.process;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ProcessRunner}'s hard kill-on-overrun timeout: a command that outlives its
 * budget is forcibly destroyed (including descendants) rather than left to run, and
 * {@code run} returns promptly instead of blocking for the command's full duration.
 */
class ProcessRunnerTest {

    private final ProcessRunner runner = new ProcessRunner();

    @Test
    void commandFinishingWithinTheTimeoutSucceeds() throws Exception {
        ProcessResult result = runner.run(List.of("sh", "-c", "echo hi"), null, 5);

        assertThat(result.timedOut()).isFalse();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output()).contains("hi");
    }

    @Test
    void commandExceedingTheTimeoutIsKilledAndReportedAsTimedOut() throws Exception {
        long start = System.nanoTime();

        ProcessResult result = runner.run(List.of("sh", "-c", "sleep 30"), null, 1);

        long elapsedSeconds = java.time.Duration.ofNanos(System.nanoTime() - start).toSeconds();
        assertThat(result.timedOut()).isTrue();
        assertThat(result.isSuccess()).isFalse();
        // Killed near the 1s budget, not left to run for the full 30s sleep.
        assertThat(elapsedSeconds).isLessThan(15);
    }

    @Test
    void nonZeroExitIsReportedAsFailureNotTimeout() throws Exception {
        ProcessResult result = runner.run(List.of("sh", "-c", "exit 3"), null, 5);

        assertThat(result.timedOut()).isFalse();
        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.isSuccess()).isFalse();
    }
}
