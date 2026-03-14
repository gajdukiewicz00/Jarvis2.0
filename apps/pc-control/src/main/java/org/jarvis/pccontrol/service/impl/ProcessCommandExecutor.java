package org.jarvis.pccontrol.service.impl;

import org.jarvis.pccontrol.service.CommandExecutor;
import org.jarvis.pccontrol.service.CommandResult;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Service
public class ProcessCommandExecutor implements CommandExecutor {

    @Override
    public CommandResult execute(List<String> command) throws IOException, InterruptedException {
        validateCommand(command);
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        try {
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int exitCode = process.waitFor();
            return new CommandResult(exitCode, output, "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    @Override
    public void start(List<String> command) throws IOException {
        validateCommand(command);
        new ProcessBuilder(command).start();
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
}
