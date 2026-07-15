package org.jarvis.llm.controller;

import org.jarvis.llm.orchestrator.LlmOrchestratorService;
import org.jarvis.llm.orchestrator.dto.OrchestrationRequest;
import org.jarvis.llm.orchestrator.dto.OrchestrationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LlmOrchestratorController}. The orchestrator service is
 * mocked; the controller is exercised directly (no MVC container) mirroring the
 * plain-Mockito style used by {@code LlmRestControllerTest}.
 */
class LlmOrchestratorControllerTest {

    private LlmOrchestratorService orchestratorService;
    private LlmOrchestratorController controller;

    @BeforeEach
    void setUp() {
        orchestratorService = mock(LlmOrchestratorService.class);
        controller = new LlmOrchestratorController(orchestratorService);
    }

    private OrchestrationRequest request() {
        OrchestrationRequest request = new OrchestrationRequest();
        request.setSessionId("session-1");
        request.setUserId("user-1");
        request.setIntent("turn_on_lights");
        return request;
    }

    private OrchestrationResponse response() {
        return new OrchestrationResponse("done", List.of(), List.of(), "raw", 0.9);
    }

    @Test
    void orchestrateReturnsOkWithProvidedCorrelationId() {
        when(orchestratorService.orchestrate(any(), eq("corr-1"))).thenReturn(response());

        ResponseEntity<OrchestrationResponse> result =
                controller.orchestrate(request(), "corr-1", "desktop-general");

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().getExplanation()).isEqualTo("done");
        verify(orchestratorService).orchestrate(any(OrchestrationRequest.class), eq("corr-1"));
    }

    @Test
    void orchestrateGeneratesCorrelationIdWhenMissing() {
        when(orchestratorService.orchestrate(any(), anyString())).thenReturn(response());

        ResponseEntity<OrchestrationResponse> result =
                controller.orchestrate(request(), null, null);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);

        ArgumentCaptor<String> correlationId = ArgumentCaptor.forClass(String.class);
        verify(orchestratorService).orchestrate(any(OrchestrationRequest.class), correlationId.capture());
        assertThat(correlationId.getValue()).isNotBlank();
    }

    @Test
    void orchestratePassesRequestThroughToService() {
        OrchestrationRequest request = request();
        when(orchestratorService.orchestrate(any(), anyString())).thenReturn(response());

        controller.orchestrate(request, "corr-2", null);

        ArgumentCaptor<OrchestrationRequest> captor = ArgumentCaptor.forClass(OrchestrationRequest.class);
        verify(orchestratorService).orchestrate(captor.capture(), eq("corr-2"));
        assertThat(captor.getValue().getSessionId()).isEqualTo("session-1");
        assertThat(captor.getValue().getIntent()).isEqualTo("turn_on_lights");
    }

    @Test
    void orchestratePropagatesServiceExceptions() {
        when(orchestratorService.orchestrate(any(), anyString()))
                .thenThrow(new IllegalStateException("orchestrator down"));

        assertThatThrownBy(() -> controller.orchestrate(request(), "corr-3", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("orchestrator down");
    }
}
