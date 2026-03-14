package org.jarvis.pccontrol.service.impl;

import org.jarvis.pccontrol.service.CommandLocator;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class PathCommandLocator implements CommandLocator {

    @Override
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
}
