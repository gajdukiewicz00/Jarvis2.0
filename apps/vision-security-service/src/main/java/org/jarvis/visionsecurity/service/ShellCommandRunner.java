package org.jarvis.visionsecurity.service;

import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Component
public class ShellCommandRunner {

    public boolean isAvailable(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        if (command.contains(File.separator)) {
            return Files.isExecutable(Path.of(command));
        }
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return false;
        }
        for (String entry : path.split(File.pathSeparator)) {
            Path candidate = Path.of(entry, command);
            if (Files.isExecutable(candidate)) {
                return true;
            }
        }
        return false;
    }

    public CommandResult execute(List<String> command) throws IOException, InterruptedException {
        validate(command);
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        try {
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int exitCode = process.waitFor();
            return new CommandResult(exitCode, output);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw ex;
        }
    }

    public void start(List<String> command) throws IOException {
        validate(command);
        new ProcessBuilder(command).start();
    }

    private void validate(List<String> command) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("Command must not be empty");
        }
        for (String part : command) {
            if (part == null || part.isBlank()) {
                throw new IllegalArgumentException("Command contains blank segment");
            }
        }
    }

    public record CommandResult(int exitCode, String output) {
    }
}
