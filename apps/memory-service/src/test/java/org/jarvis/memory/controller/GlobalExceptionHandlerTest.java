package org.jarvis.memory.controller;

import org.jarvis.memory.exception.DuplicateMemoryException;
import org.jarvis.memory.exception.MemoryDependencyUnavailableException;
import org.jarvis.memory.exception.MemoryExportEncryptionUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.WebRequest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleDependencyUnavailableBuildsServiceUnavailableBodyWithPathAndDependency() {
        MemoryDependencyUnavailableException ex = new MemoryDependencyUnavailableException(
                "embedding-service", "embedding-service timed out", new RuntimeException("timeout"));
        WebRequest request = mock(WebRequest.class);
        when(request.getDescription(false)).thenReturn("uri=/memory/search");

        ResponseEntity<Map<String, Object>> response = handler.handleDependencyUnavailable(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("status")).isEqualTo(503);
        assertThat(body.get("error")).isEqualTo("EMBEDDING_SERVICE_UNAVAILABLE");
        assertThat(body.get("message")).isEqualTo("embedding-service timed out");
        assertThat(body.get("service")).isEqualTo("memory-service");
        assertThat(body.get("dependency")).isEqualTo("embedding-service");
        assertThat(body.get("path")).isEqualTo("/memory/search");
        assertThat(body.get("timestamp")).isNotNull();
    }

    @Test
    void handleDuplicateMemoryBuildsConflictBodyWithExistingMemoryId() {
        DuplicateMemoryException ex = new DuplicateMemoryException("mem-existing");
        WebRequest request = mock(WebRequest.class);
        when(request.getDescription(false)).thenReturn("uri=/api/v1/memory/notes");

        ResponseEntity<Map<String, Object>> response = handler.handleDuplicateMemory(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("status")).isEqualTo(409);
        assertThat(body.get("error")).isEqualTo("DUPLICATE_MEMORY_NOTE");
        assertThat(body.get("dependency")).isEqualTo("mem-existing");
    }

    @Test
    void handleExportEncryptionUnavailableBuildsBadRequestBody() {
        MemoryExportEncryptionUnavailableException ex =
                new MemoryExportEncryptionUnavailableException("encryption key not configured");
        WebRequest request = mock(WebRequest.class);
        when(request.getDescription(false)).thenReturn("uri=/api/v1/memory/notes/export/encrypted");

        ResponseEntity<Map<String, Object>> response = handler.handleExportEncryptionUnavailable(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("status")).isEqualTo(400);
        assertThat(body.get("error")).isEqualTo("MEMORY_EXPORT_ENCRYPTION_UNAVAILABLE");
        assertThat(body.get("message")).isEqualTo("encryption key not configured");
    }
}
