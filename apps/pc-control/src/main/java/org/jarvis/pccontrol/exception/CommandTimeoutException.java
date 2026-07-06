package org.jarvis.pccontrol.exception;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * Thrown when an externally executed command does not finish within its configured
 * timeout. The offending process (and any descendant processes it spawned) has
 * already been forcibly killed by the time this exception is thrown.
 *
 * <p>Extends {@link IOException} so existing {@code catch (IOException e)} call
 * sites treat a timeout as the failure it is, rather than silently succeeding.
 */
public class CommandTimeoutException extends IOException {

    public CommandTimeoutException(List<String> command, Duration timeout) {
        super("Command timed out after " + timeout.toMillis() + "ms and was killed: " + safeCommand(command));
    }

    private static String safeCommand(List<String> command) {
        // Command argv can legitimately contain user-supplied values (paths, urls,
        // hotkeys); none of the callers in this module pass secrets as argv, but we
        // still avoid echoing anything beyond the program name plus arg count so a
        // future caller cannot accidentally leak a credential via this message.
        if (command == null || command.isEmpty()) {
            return "<empty>";
        }
        return command.get(0) + " (" + (command.size() - 1) + " arg(s))";
    }
}
