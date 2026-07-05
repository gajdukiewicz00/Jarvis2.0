package org.jarvis.media.web;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.media.job.ArtifactNotFoundException;
import org.jarvis.media.job.JobNotFoundException;
import org.jarvis.media.probe.ProbeException;
import org.jarvis.media.workspace.PathValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

/**
 * Maps media exceptions to safe HTTP responses. Client bodies carry only a generic
 * message — never stack traces, internal paths, or SQL.
 */
@Slf4j
@RestControllerAdvice(basePackages = "org.jarvis.media")
public class MediaExceptionHandler {

    @ExceptionHandler(PathValidationException.class)
    public ResponseEntity<Map<String, Object>> handlePath(PathValidationException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(JobNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(JobNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ArtifactNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleArtifactNotFound(ArtifactNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ProbeException.class)
    public ResponseEntity<Map<String, Object>> handleProbe(ProbeException e) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler(UnauthenticatedException.class)
    public ResponseEntity<Map<String, Object>> handleUnauth(UnauthenticatedException e) {
        return error(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(MediaDisabledException.class)
    public ResponseEntity<Map<String, Object>> handleDisabled(MediaDisabledException e) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(RejectedExecutionException.class)
    public ResponseEntity<Map<String, Object>> handleSaturated(RejectedExecutionException e) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "media executor saturated; retry later");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception e) {
        log.error("Unexpected media error", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("success", false, "error", message == null ? "" : message));
    }
}
