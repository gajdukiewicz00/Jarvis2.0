package org.jarvis.apigateway.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentExecutionControllerTest {

    @Mock
    private AgentExecutionService agentExecutionService;

    @Mock
    private Authentication authentication;

    private AgentExecutionController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentExecutionController(agentExecutionService);
    }

    @Test
    void executeReturnsUnauthorizedWhenAuthenticationIsNull() {
        ResponseEntity<Map<String, Object>> response = controller.execute(null,
                new AgentExecutionController.ExecuteRequest(null, "buy milk", null, null, null, null));

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).containsEntry("error", "unauthenticated");
        verifyNoInteractions(agentExecutionService);
    }

    @Test
    void executeReturnsUnauthorizedWhenAuthenticationNameIsBlank() {
        when(authentication.getName()).thenReturn("   ");

        ResponseEntity<Map<String, Object>> response = controller.execute(authentication,
                new AgentExecutionController.ExecuteRequest(null, "buy milk", null, null, null, null));

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(agentExecutionService);
    }

    @Test
    void executeReturnsBadRequestWhenBodyIsNull() {
        when(authentication.getName()).thenReturn("user-1");

        ResponseEntity<Map<String, Object>> response = controller.execute(authentication, null);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).containsEntry("error", "intent is required");
        verifyNoInteractions(agentExecutionService);
    }

    @Test
    void executeReturnsBadRequestWhenIntentIsBlank() {
        when(authentication.getName()).thenReturn("user-1");

        ResponseEntity<Map<String, Object>> response = controller.execute(authentication,
                new AgentExecutionController.ExecuteRequest("session-1", "   ", null, null, null, null));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(agentExecutionService);
    }

    @Test
    void executeDerivesSessionIdAndDefaultsDryRunToFalse() {
        when(authentication.getName()).thenReturn("user-7");
        Map<String, Object> serviceResult = Map.of("explanation", "done");
        when(agentExecutionService.execute(anyString(), anyString(), anyString(), any(), any(), any(), anyBoolean()))
                .thenReturn(serviceResult);

        ResponseEntity<Map<String, Object>> response = controller.execute(authentication,
                new AgentExecutionController.ExecuteRequest(null, "add a todo", true, "en", 3, null));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(serviceResult);
        verify(agentExecutionService).execute(
                eq("user-7"), eq("agent-user-7"), eq("add a todo"), eq(true), eq("en"), eq(3), eq(false));
    }

    @Test
    void executeUsesProvidedSessionIdAndDryRunFlag() {
        when(authentication.getName()).thenReturn("user-8");
        when(agentExecutionService.execute(anyString(), anyString(), anyString(), any(), any(), anyInt(), anyBoolean()))
                .thenReturn(Map.of());

        controller.execute(authentication,
                new AgentExecutionController.ExecuteRequest("explicit-session", "add a todo", false, "ru", 5, true));

        verify(agentExecutionService).execute(
                eq("user-8"), eq("explicit-session"), eq("add a todo"), eq(false), eq("ru"), eq(5), eq(true));
    }
}
