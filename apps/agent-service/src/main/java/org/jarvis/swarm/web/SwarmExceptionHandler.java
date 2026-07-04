package org.jarvis.swarm.web;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.swarm.permission.PanicEngagedException;
import org.jarvis.swarm.permission.PermissionDeniedException;
import org.jarvis.swarm.run.SwarmNotFoundException;
import org.jarvis.swarm.sandbox.SandboxException;
import org.jarvis.swarm.task.InvalidTransitionException;
import org.jarvis.swarm.task.TaskNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

/**
 * Maps swarm exceptions to safe HTTP responses. Bodies carry only a generic message —
 * never stack traces, internal paths, or command output.
 */
@Slf4j
@RestControllerAdvice(basePackages = "org.jarvis.swarm")
public class SwarmExceptionHandler {

    @ExceptionHandler(SandboxException.class)
    public ResponseEntity<Map<String, Object>> handleSandbox(SandboxException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(TaskNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(TaskNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(SwarmNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleSwarmNotFound(SwarmNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(InvalidTransitionException.class)
    public ResponseEntity<Map<String, Object>> handleTransition(InvalidTransitionException e) {
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(PermissionDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleDenied(PermissionDeniedException e) {
        return error(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(PanicEngagedException.class)
    public ResponseEntity<Map<String, Object>> handlePanic(PanicEngagedException e) {
        return error(HttpStatus.LOCKED, e.getMessage());
    }

    @ExceptionHandler(UnauthenticatedException.class)
    public ResponseEntity<Map<String, Object>> handleUnauth(UnauthenticatedException e) {
        return error(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(SwarmDisabledException.class)
    public ResponseEntity<Map<String, Object>> handleDisabled(SwarmDisabledException e) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(RejectedExecutionException.class)
    public ResponseEntity<Map<String, Object>> handleSaturated(RejectedExecutionException e) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "agent queue saturated; retry later");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception e) {
        log.error("Unexpected swarm error", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("success", false, "error", message == null ? "" : message));
    }
}
