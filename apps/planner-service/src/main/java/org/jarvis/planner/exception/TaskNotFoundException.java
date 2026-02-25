package org.jarvis.planner.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class TaskNotFoundException extends RuntimeException {

    public TaskNotFoundException(Long taskId) {
        super("Task not found: " + taskId);
    }

    public TaskNotFoundException(Long taskId, String userId) {
        super("Task not found for user " + userId + ": " + taskId);
    }
}
