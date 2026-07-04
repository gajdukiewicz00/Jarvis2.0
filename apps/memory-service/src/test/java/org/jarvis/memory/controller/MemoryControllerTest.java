package org.jarvis.memory.controller;

import org.jarvis.memory.dto.IngestRequest;
import org.jarvis.memory.dto.SearchRequest;
import org.jarvis.memory.dto.SearchResponse;
import org.jarvis.memory.dto.SummarizeRequest;
import org.jarvis.memory.service.MemoryDependencyStatusService;
import org.jarvis.memory.service.MemoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Direct-instantiation unit tests for {@link MemoryController} (no Spring
 * context / MockMvc) — mirrors the plain-mock style already used for
 * {@code MemoryNoteControllerTest}. Authentication is stubbed straight onto
 * {@link SecurityContextHolder} since {@code requireUserId()} reads it
 * directly rather than via an injected principal.
 */
class MemoryControllerTest {

    private MemoryService memoryService;
    private MemoryDependencyStatusService dependencyStatusService;
    private MemoryController controller;

    @BeforeEach
    void setUp() {
        memoryService = mock(MemoryService.class);
        dependencyStatusService = mock(MemoryDependencyStatusService.class);
        controller = new MemoryController(memoryService, dependencyStatusService);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String userId) {
        Authentication authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getName()).thenReturn(userId);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @Test
    void ingestStampsUserIdAndReturnsProcessingSummary() {
        authenticateAs("user-1");
        IngestRequest request = IngestRequest.builder()
                .sessionId("session-1")
                .messages(List.of(IngestRequest.MessageDto.builder()
                        .role("user").content("hi").build()))
                .build();

        ResponseEntity<Map<String, Object>> response = controller.ingest(request, "corr-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("status")).isEqualTo("ok");
        assertThat(response.getBody().get("messagesIngested")).isEqualTo(1);
        assertThat(request.getUserId()).isEqualTo("user-1");
        verify(memoryService).ingest(request, "corr-1");
    }

    @Test
    void ingestGeneratesCorrelationIdWhenHeaderMissing() {
        authenticateAs("user-1");
        IngestRequest request = IngestRequest.builder()
                .sessionId("session-1")
                .messages(List.of(IngestRequest.MessageDto.builder()
                        .role("user").content("hi").build()))
                .build();

        controller.ingest(request, null);

        verify(memoryService).ingest(eq(request), any(String.class));
    }

    @Test
    void ingestThrowsUnauthorizedWhenNoAuthentication() {
        IngestRequest request = IngestRequest.builder()
                .sessionId("s").messages(List.of()).build();

        assertThatThrownBy(() -> controller.ingest(request, "corr"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Missing authentication");
    }

    @Test
    void ingestThrowsUnauthorizedWhenAuthenticationNotAuthenticated() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(false);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        IngestRequest request = IngestRequest.builder()
                .sessionId("s").messages(List.of()).build();

        assertThatThrownBy(() -> controller.ingest(request, "corr"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void ingestAsyncStampsUserIdAndReturnsAccepted() {
        authenticateAs("user-2");
        IngestRequest request = IngestRequest.builder()
                .sessionId("session-2")
                .messages(List.of(IngestRequest.MessageDto.builder()
                        .role("assistant").content("hey").build()))
                .build();

        ResponseEntity<Map<String, Object>> response = controller.ingestAsync(request, "corr-2");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().get("status")).isEqualTo("accepted");
        assertThat(response.getBody().get("correlationId")).isEqualTo("corr-2");
        assertThat(request.getUserId()).isEqualTo("user-2");
        verify(memoryService).ingestAsync(request, "corr-2");
    }

    @Test
    void searchStampsUserIdAndDelegatesToService() {
        authenticateAs("user-3");
        SearchRequest request = SearchRequest.builder().query("find me something").build();
        SearchResponse serviceResponse = SearchResponse.builder()
                .chunks(List.of())
                .retrievalMode("semantic")
                .build();
        when(memoryService.search(request, "corr-3")).thenReturn(serviceResponse);

        ResponseEntity<SearchResponse> response = controller.search(request, "corr-3");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(serviceResponse);
        assertThat(request.getUserId()).isEqualTo("user-3");
    }

    @Test
    void summarizeSessionStampsUserIdAndReturnsSummary() {
        authenticateAs("user-4");
        SummarizeRequest request = SummarizeRequest.builder().sessionId("session-4").build();

        ResponseEntity<Map<String, Object>> response = controller.summarizeSession(request, "corr-4");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("sessionId")).isEqualTo("session-4");
        assertThat(request.getUserId()).isEqualTo("user-4");
        verify(memoryService).summarizeSession(request, "corr-4");
    }

    @Test
    void healthReturnsOkWhenDependenciesHealthy() {
        MemoryDependencyStatusService.DependencyStatus status = MemoryDependencyStatusService.DependencyStatus.builder()
                .status("healthy")
                .database("up")
                .pgvector("available")
                .embeddingService("up")
                .embeddingModel("m")
                .embeddingDimension(384)
                .embeddingError(null)
                .build();
        when(dependencyStatusService.checkDependencies()).thenReturn(status);

        ResponseEntity<Map<String, Object>> response = controller.health();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("status")).isEqualTo("healthy");
        assertThat(response.getBody().get("embeddingModel")).isEqualTo("m");
        assertThat(response.getBody().get("embeddingDimension")).isEqualTo(384);
        assertThat(response.getBody().get("embeddingError")).isEqualTo("");
    }

    @Test
    void healthReturnsServiceUnavailableWhenDegradedAndDefaultsUnknownFields() {
        MemoryDependencyStatusService.DependencyStatus status = MemoryDependencyStatusService.DependencyStatus.builder()
                .status("degraded")
                .database("down")
                .pgvector("missing")
                .embeddingService("down")
                .embeddingModel(null)
                .embeddingDimension(null)
                .embeddingError("timeout")
                .build();
        when(dependencyStatusService.checkDependencies()).thenReturn(status);

        ResponseEntity<Map<String, Object>> response = controller.health();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().get("embeddingModel")).isEqualTo("unknown");
        assertThat(response.getBody().get("embeddingDimension")).isEqualTo(0);
        assertThat(response.getBody().get("embeddingError")).isEqualTo("timeout");
    }
}
