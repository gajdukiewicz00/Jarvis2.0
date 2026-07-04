package org.jarvis.swarm.sandbox;

import java.nio.file.Path;

/** A task's isolated working directory. All agent writes stay inside {@link #dir}. */
public record Sandbox(String taskId, Path dir) {

    public String path() {
        return dir.toString();
    }
}
