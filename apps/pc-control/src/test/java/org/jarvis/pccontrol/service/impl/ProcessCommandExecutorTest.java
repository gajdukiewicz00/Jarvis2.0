package org.jarvis.pccontrol.service.impl;

import org.jarvis.pccontrol.service.CommandResult;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ProcessCommandExecutor wraps ProcessBuilder directly rather than an injectable
 * abstraction, so these tests exercise it against real, universally-available
 * POSIX utilities (echo/true/false) instead of mocking - there is nothing to mock.
 */
class ProcessCommandExecutorTest {

    private final ProcessCommandExecutor executor = new ProcessCommandExecutor();

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
}
