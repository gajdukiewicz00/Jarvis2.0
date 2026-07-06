package org.jarvis.pccontrol.service.impl;

import org.jarvis.pccontrol.exception.CommandTimeoutException;
import org.jarvis.pccontrol.service.CommandResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ProcessCommandExecutor wraps ProcessBuilder directly rather than an injectable
 * abstraction, so these tests exercise it against real, universally-available
 * POSIX utilities (echo/true/false/sleep) instead of mocking - there is nothing to mock.
 */
class ProcessCommandExecutorTest {

    private static final long GENEROUS_DEFAULT_TIMEOUT_SECONDS = 30;

    private final ProcessCommandExecutor executor =
            new ProcessCommandExecutor(GENEROUS_DEFAULT_TIMEOUT_SECONDS);

    @Test
    void executeCapturesStdoutAndExitCodeForSuccessfulCommand() throws Exception {
        CommandResult result = executor.execute(List.of("echo", "hello-jarvis"));

        assertEquals(0, result.exitCode());
        assertEquals("hello-jarvis", result.stdout());
    }

    @Test
    void executeCapturesNonZeroExitCodeForFailingCommand() throws Exception {
        CommandResult result = executor.execute(List.of("false"));

        assertEquals(1, result.exitCode());
    }

    @Test
    void executeRejectsNullCommand() {
        assertThrows(IllegalArgumentException.class, () -> executor.execute(null));
    }

    @Test
    void executeRejectsEmptyCommand() {
        assertThrows(IllegalArgumentException.class, () -> executor.execute(List.of()));
    }

    @Test
    void executeRejectsBlankSegment() {
        assertThrows(IllegalArgumentException.class, () -> executor.execute(Arrays.asList("echo", "  ")));
    }

    @Test
    void executeRejectsNullSegment() {
        assertThrows(IllegalArgumentException.class, () -> executor.execute(Arrays.asList("echo", null)));
    }

    @Test
    void startLaunchesProcessWithoutWaitingForCompletion() {
        assertDoesNotThrow(() -> executor.start(List.of("true")));
    }

    @Test
    void startRejectsEmptyCommand() {
        assertThrows(IllegalArgumentException.class, () -> executor.start(List.of()));
    }

    @Test
    void startRejectsBlankSegment() {
        assertThrows(IllegalArgumentException.class, () -> executor.start(Arrays.asList("true", "")));
    }

    @Test
    void constructorRejectsNonPositiveDefaultTimeout() {
        assertThrows(IllegalArgumentException.class, () -> new ProcessCommandExecutor(0));
        assertThrows(IllegalArgumentException.class, () -> new ProcessCommandExecutor(-5));
    }

    @Test
    void executeWithExplicitTimeoutSucceedsWhenCommandFinishesInTime() throws Exception {
        CommandResult result = executor.execute(List.of("echo", "within-budget"), Duration.ofSeconds(5));

        assertEquals(0, result.exitCode());
        assertEquals("within-budget", result.stdout());
    }

    @Test
    void executeKillsAndThrowsWhenCommandExceedsTimeout() {
        long startNanos = System.nanoTime();

        assertThrows(CommandTimeoutException.class,
                () -> executor.execute(List.of("sleep", "30"), Duration.ofMillis(200)));

        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
        // The command asked to sleep 30s; a correctly enforced timeout kills it in a
        // couple hundred ms. A generous upper bound keeps this robust on slow CI while
        // still failing if the process were allowed to run anywhere near its full duration.
        assertTrue(elapsedMillis < 10_000,
                "Expected the overrunning process to be killed well under 10s, took " + elapsedMillis + "ms");
    }

    @Test
    void executeRejectsNullTimeout() {
        assertThrows(IllegalArgumentException.class, () -> executor.execute(List.of("echo", "hi"), null));
    }

    @Test
    void executeRejectsZeroOrNegativeTimeout() {
        assertThrows(IllegalArgumentException.class,
                () -> executor.execute(List.of("echo", "hi"), Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> executor.execute(List.of("echo", "hi"), Duration.ofSeconds(-1)));
    }

    @Test
    void executeKillsDescendantProcessesSpawnedByATimedOutCommand() throws Exception {
        // "bash -c '<cmd> & wait'" forces bash to fork a genuine child process and
        // stay alive waiting on it (unlike a single simple command, which bash would
        // just exec into directly) - this reproduces a real parent+descendant tree
        // so we can verify killProcessTree() reaches the descendant, not just the
        // immediate child ProcessBuilder started.
        String uniqueDurationMarker = "9171." + (System.nanoTime() % 100_000);
        List<String> command = List.of("bash", "-c", "sleep " + uniqueDurationMarker + " & wait");

        assertThrows(CommandTimeoutException.class, () -> executor.execute(command, Duration.ofMillis(300)));

        assertFalse(processStillRunning("sleep " + uniqueDurationMarker),
                "descendant 'sleep " + uniqueDurationMarker + "' process should have been killed");
    }

    /**
     * Polls `pgrep -f` (via this same executor, so it also doubles as a realistic
     * usage of the class) to confirm no process matching {@code pattern} survives,
     * tolerating the short window the OS needs to actually reap a killed process.
     */
    private boolean processStillRunning(String pattern) throws Exception {
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadlineNanos) {
            CommandResult check = executor.execute(List.of("pgrep", "-f", pattern));
            if (check.exitCode() != 0) {
                return false;
            }
            Thread.sleep(100);
        }
        return true;
    }
}
