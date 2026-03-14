package org.jarvis.pccontrol.service;

public record CommandResult(int exitCode, String stdout, String stderr) {

    public CommandResult {
        stdout = stdout == null ? "" : stdout;
        stderr = stderr == null ? "" : stderr;
    }
}
