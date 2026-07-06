package org.jarvis.pccontrol.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.pccontrol.exception.CommandTimeoutException;
import org.jarvis.pccontrol.service.CommandExecutor;
import org.jarvis.pccontrol.service.CommandResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ProcessCommandExecutor implements CommandExecutor {

    /** How long a killed process is given to actually die before we give up waiting. */
    private static final Duration TERMINATION_GRACE_PERIOD = Duration.ofSeconds(2);

    private final Duration defaultTimeout;

    public ProcessCommandExecutor(
            @Value("${pc-control.command-timeout-seconds:30}") long defaultTimeoutSeconds) {
        if (defaultTimeoutSeconds <= 0) {
            throw new IllegalArgumentException(
                    "pc-control.command-timeout-seconds must be positive, got " + defaultTimeoutSeconds);
        }
        this.defaultTimeout = Duration.ofSeconds(defaultTimeoutSeconds);
    }

    @Override
    public CommandResult execute(List<String> command) throws IOException, InterruptedException {
        return execute(command, defaultTimeout);
    }

    @Override
    public CommandResult execute(List<String> command, Duration timeout) throws IOException, InterruptedException {
        validateCommand(command);
        validateTimeout(timeout);

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();

        // Drain stdout concurrently on a daemon thread rather than reading it after
        // waitFor(): the child can otherwise fill its stdout pipe buffer and block
        // forever on write(), which would starve waitFor() until our timeout fires
        // for a reason unrelated to the command actually hanging.
        StdoutGobbler gobbler = new StdoutGobbler(process.getInputStream());
        Thread readerThread = new Thread(gobbler, "pc-control-cmd-reader");
        readerThread.setDaemon(true);
        readerThread.start();

        try {
            boolean finishedInTime = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finishedInTime) {
                killProcessTree(process);
                readerThread.interrupt();
                throw new CommandTimeoutException(command, timeout);
            }
            readerThread.join(TERMINATION_GRACE_PERIOD.toMillis());
            return new CommandResult(process.exitValue(), gobbler.output().trim(), "");
        } catch (InterruptedException e) {
            killProcessTree(process);
            readerThread.interrupt();
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    @Override
    public void start(List<String> command) throws IOException {
        validateCommand(command);
        new ProcessBuilder(command).start();
    }

    /**
     * Forcibly kills {@code process} and every descendant process it spawned
     * (e.g. a shell wrapper's children), then waits briefly for termination so the
     * kill has actually taken effect before we return control to the caller.
     */
    private static void killProcessTree(Process process) {
        process.descendants().forEach(ProcessCommandExecutor::destroyForciblyQuietly);
        process.destroyForcibly();
        try {
            boolean terminated = process.waitFor(TERMINATION_GRACE_PERIOD.toMillis(), TimeUnit.MILLISECONDS);
            if (!terminated) {
                log.warn("Process {} did not terminate within the grace period after destroyForcibly()",
                        process.pid());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void destroyForciblyQuietly(ProcessHandle handle) {
        try {
            handle.destroyForcibly();
        } catch (RuntimeException e) {
            log.debug("Failed to forcibly destroy descendant process {}: {}", handle.pid(), e.getMessage());
        }
    }

    private static void validateCommand(List<String> command) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("Command must not be empty");
        }
        for (String part : command) {
            if (part == null || part.isBlank()) {
                throw new IllegalArgumentException("Command contains blank segment");
            }
        }
    }

    private static void validateTimeout(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Timeout must be a positive duration");
        }
    }

    /** Reads an InputStream to completion on a background thread. */
    private static final class StdoutGobbler implements Runnable {
        private final InputStream inputStream;
        private volatile byte[] captured = new byte[0];

        private StdoutGobbler(InputStream inputStream) {
            this.inputStream = inputStream;
        }

        @Override
        public void run() {
            try (ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                inputStream.transferTo(buffer);
                captured = buffer.toByteArray();
            } catch (IOException e) {
                // The stream is expected to break when the process is killed; there is
                // nothing actionable to do beyond keeping whatever was captured so far.
                log.debug("Stdout capture interrupted: {}", e.getMessage());
            }
        }

        String output() {
            return new String(captured, StandardCharsets.UTF_8);
        }
    }
}
