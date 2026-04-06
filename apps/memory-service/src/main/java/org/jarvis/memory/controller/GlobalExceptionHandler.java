package org.jarvis.memory.controller;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.memory.exception.MemoryDependencyUnavailableException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {

    @ExceptionHandler(MemoryDependencyUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleDependencyUnavailable(
            MemoryDependencyUnavailableException ex,
            WebRequest request) {

        log.warn("Memory dependency unavailable [{}]: {}", ex.getDependency(), ex.getMessage());
        return buildErrorResponse(
                HttpStatus.SERVICE_UNAVAILABLE,
                "EMBEDDING_SERVICE_UNAVAILABLE",
                ex.getMessage(),
                ex.getDependency(),
                request);
    }

    private ResponseEntity<Map<String, Object>> buildErrorResponse(
            HttpStatus status,
            String errorCode,
            String message,
            String dependency,
            WebRequest request) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", status.value());
        body.put("error", errorCode);
        body.put("message", message);
        body.put("service", "memory-service");
        body.put("dependency", dependency);
        body.put("path", extractPath(request));

        return new ResponseEntity<>(body, status);
    }

    private String extractPath(WebRequest request) {
        String description = request.getDescription(false);
        return description.replace("uri=", "");
    }
}
