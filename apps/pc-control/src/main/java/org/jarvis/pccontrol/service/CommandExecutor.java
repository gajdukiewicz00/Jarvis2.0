package org.jarvis.pccontrol.service;

import java.io.IOException;
import java.util.List;

public interface CommandExecutor {

    CommandResult execute(List<String> command) throws IOException, InterruptedException;

    void start(List<String> command) throws IOException;
}
