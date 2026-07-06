package org.jarvis.pccontrol.service;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

public interface CommandExecutor {

    /**
     * Runs {@code command} to completion using this executor's configured default
     * per-command timeout, killing the process (and any descendants) if it overruns.
     *
     * @see #execute(List, Duration)
     */
    CommandResult execute(List<String> command) throws IOException, InterruptedException;

    /**
     * Runs {@code command} to completion, forcibly killing the process (and any
     * descendant processes it spawned) if it has not exited within {@code timeout}.
     *
     * @throws org.jarvis.pccontrol.exception.CommandTimeoutException if the command
     *         does not complete within {@code timeout}
     */
    CommandResult execute(List<String> command, Duration timeout) throws IOException, InterruptedException;

    void start(List<String> command) throws IOException;
}
