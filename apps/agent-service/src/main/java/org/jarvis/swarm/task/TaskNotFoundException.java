package org.jarvis.swarm.task;

/** Thrown when a task id is unknown or not owned by the caller. Mapped to HTTP 404. */
public class TaskNotFoundException extends RuntimeException {
    public TaskNotFoundException(String id) {
        super("Agent task not found: " + id);
    }
}
