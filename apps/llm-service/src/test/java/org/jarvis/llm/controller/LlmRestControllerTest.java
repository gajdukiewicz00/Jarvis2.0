package org.jarvis.llm.controller;

import org.jarvis.llm.client.LlmClient;
import org.jarvis.llm.client.MemoryClient;
import org.jarvis.llm.dto.ChatMessageDto;
import org.jarvis.llm.dto.ChatRequestDto;
import org.jarvis.llm.dto.ChatResponseDto;
import org.jarvis.llm.dto.DialogRequest;
import org.jarvis.llm.dto.DialogResponse;
import org.jarvis.llm.model.Emotion;
import org.jarvis.llm.service.AiRuntimeStatusService;
import org.jarvis.llm.service.LlmAdmissionController;
import org.jarvis.llm.service.LlmLifecycleManager;
import org.jarvis.llm.service.LlmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmRestControllerTest {

    private LlmService llmService;
    private AiRuntimeStatusService aiRuntimeStatusService;
    private LlmLifecycleManager lifecycleManager;
    private LlmAdmissionController admissionController;
    private LlmRestController controller;

    @BeforeEach
    void setUp() {
        llmService = mock(LlmService.class);
        aiRuntimeStatusService = mock(AiRuntimeStatusService.class);
        lifecycleManager = mock(LlmLifecycleManager.class);
        admissionController = new LlmAdmissionController(1, 4);
        controller = new LlmRestController(llmService, aiRuntimeStatusService, lifecycleManager, admissionController);
    }

    private ChatRequestDto chatRequest() {
        ChatMessageDto message = new ChatMessageDto(ChatMessageDto.Role.USER, "Привет");
        return new ChatRequestDto("session-1", List.of(message), 256, 0.5);
    }

    // ── /chat ─────────────────────────────────────────────────────────

    @Test
    void chatReturnsOkOnSuccess() {
        when(llmService.processMessage(anyString(), any(), anyString(), anyString(), anyBoolean(), any()))
                .thenReturn(new ChatResponseDto("Готово", null, "model", 10, Emotion.NEUTRAL));

        ResponseEntity<ChatResponseDto> response = controller.chat(chatRequest(), null, "corr-1", null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Готово", response.getBody().getReply());
    }

    @Test
    void chatGeneratesCorrelationIdWhenMissing() {
        when(llmService.processMessage(anyString(), any(), anyString(), anyString(), anyBoolean(), any()))
                .thenReturn(new ChatResponseDto("ok", null, "model", 1, Emotion.NEUTRAL));

        ResponseEntity<ChatResponseDto> response = controller.chat(chatRequest(), null, null, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void chatMapsTimeoutCauseToGatewayTimeout() {
        when(llmService.processMessage(anyString(), any(), anyString(), anyString(), anyBoolean(), any()))
                .thenThrow(new LlmClient.LlmClientException("timeout", new ResourceAccessException("timed out")));

        ResponseEntity<ChatResponseDto> response = controller.chat(chatRequest(), null, "corr-2", null);

        assertEquals(HttpStatus.GATEWAY_TIMEOUT, response.getStatusCode());
    }

    @Test
    void chatMapsServerErrorCauseToServiceUnavailable() {
        HttpServerErrorException serverError = new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR, "boom");
        when(llmService.processMessage(anyString(), any(), anyString(), anyString(), anyBoolean(), any()))
                .thenThrow(new LlmClient.LlmClientException("5xx", serverError));

        ResponseEntity<ChatResponseDto> response = controller.chat(chatRequest(), null, "corr-3", null);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    }

    @Test
    void chatMapsClientErrorCauseToBadRequest() {
        HttpClientErrorException clientError = new HttpClientErrorException(HttpStatus.BAD_REQUEST, "bad");
        when(llmService.processMessage(anyString(), any(), anyString(), anyString(), anyBoolean(), any()))
                .thenThrow(new LlmClient.LlmClientException("4xx", clientError));

        ResponseEntity<ChatResponseDto> response = controller.chat(chatRequest(), null, "corr-4", null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void chatMapsUnknownClientExceptionCauseToServiceUnavailable() {
        when(llmService.processMessage(anyString(), any(), anyString(), anyString(), anyBoolean(), any()))
                .thenThrow(new LlmClient.LlmClientException("unexpected", new RuntimeException("boom")));

        ResponseEntity<ChatResponseDto> response = controller.chat(chatRequest(), null, "corr-5", null);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    }

    @Test
    void chatMapsMemoryClientExceptionToServiceUnavailable() {
        when(llmService.processMessage(anyString(), any(), anyString(), anyString(), anyBoolean(), any()))
                .thenThrow(new MemoryClient.MemoryClientException("memory down"));

        ResponseEntity<ChatResponseDto> response = controller.chat(chatRequest(), null, "corr-6", null);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    }

    @Test
    void chatMapsGenericRuntimeExceptionToInternalServerError() {
        when(llmService.processMessage(anyString(), any(), anyString(), anyString(), anyBoolean(), any()))
                .thenThrow(new RuntimeException("kaboom"));

        ResponseEntity<ChatResponseDto> response = controller.chat(chatRequest(), null, "corr-7", null);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    }

    @Test
    void chatUsesDelegatedUserIdFromAuthenticationDetails() {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                "delegated-user", null, List.of(new SimpleGrantedAuthority("USER")));
        ((UsernamePasswordAuthenticationToken) auth).setDetails("delegated-by:orchestrator");

        when(llmService.processMessage(anyString(), eq("delegated-user"), anyString(), anyString(),
                eq(true), any()))
                .thenReturn(new ChatResponseDto("ok", null, "model", 1, Emotion.NEUTRAL));

        ResponseEntity<ChatResponseDto> response = controller.chat(chatRequest(), auth, "corr-8", null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(llmService).processMessage(eq("session-1"), eq("delegated-user"), eq("Привет"),
                eq("corr-8"), eq(true), any());
    }

    @Test
    void chatUsesNullUserIdForInternalServiceRequest() {
        Authentication auth = new UsernamePasswordAuthenticationToken(
                "orchestrator", null, List.of(new SimpleGrantedAuthority("SVC_INTERNAL")));

        when(llmService.processMessage(anyString(), eq(null), anyString(), anyString(), eq(false), any()))
                .thenReturn(new ChatResponseDto("ok", null, "model", 1, Emotion.NEUTRAL));

        ResponseEntity<ChatResponseDto> response = controller.chat(chatRequest(), auth, "corr-9", null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(llmService).processMessage(eq("session-1"), eq(null), eq("Привет"),
                eq("corr-9"), eq(false), any());
    }

    // ── /dialog ───────────────────────────────────────────────────────

    @Test
    void dialogReturnsOkOnSuccess() {
        DialogRequest request = new DialogRequest();
        request.setSessionId("session-1");
        request.setUserId("user-1");
        request.setInput("Привет");

        when(llmService.processDialog(any(), anyString())).thenReturn(DialogResponse.builder()
                .sessionId("session-1")
                .reply("Здравствуйте")
                .shouldContinue(true)
                .mode("dialog")
                .build());

        ResponseEntity<DialogResponse> response = controller.dialog(request, null, "corr-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Здравствуйте", response.getBody().getReply());
    }

    @Test
    void dialogFillsUserIdFromAuthenticationWhenBlank() {
        DialogRequest request = new DialogRequest();
        request.setSessionId("session-1");
        request.setInput("Привет");

        Authentication auth = new UsernamePasswordAuthenticationToken(
                "user-42", null, List.of(new SimpleGrantedAuthority("USER")));

        when(llmService.processDialog(any(), anyString())).thenReturn(DialogResponse.builder()
                .sessionId("session-1").reply("ok").shouldContinue(true).mode("dialog").build());

        controller.dialog(request, auth, "corr-2");

        assertEquals("user-42", request.getUserId());
    }

    @Test
    void dialogMapsTimeoutCauseToGatewayTimeout() {
        DialogRequest request = new DialogRequest();
        request.setSessionId("session-1");
        request.setInput("Привет");

        when(llmService.processDialog(any(), anyString()))
                .thenThrow(new LlmClient.LlmClientException("timeout", new ResourceAccessException("timed out")));

        ResponseEntity<DialogResponse> response = controller.dialog(request, null, null);

        assertEquals(HttpStatus.GATEWAY_TIMEOUT, response.getStatusCode());
    }

    @Test
    void dialogMapsOtherLlmClientExceptionToServiceUnavailable() {
        DialogRequest request = new DialogRequest();
        request.setSessionId("session-1");
        request.setInput("Привет");

        when(llmService.processDialog(any(), anyString()))
                .thenThrow(new LlmClient.LlmClientException("boom", new RuntimeException("x")));

        ResponseEntity<DialogResponse> response = controller.dialog(request, null, null);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    }

    @Test
    void dialogMapsMemoryClientExceptionToServiceUnavailable() {
        DialogRequest request = new DialogRequest();
        request.setSessionId("session-1");
        request.setInput("Привет");

        when(llmService.processDialog(any(), anyString()))
                .thenThrow(new MemoryClient.MemoryClientException("memory down"));

        ResponseEntity<DialogResponse> response = controller.dialog(request, null, null);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    }

    @Test
    void dialogMapsGenericRuntimeExceptionToInternalServerError() {
        DialogRequest request = new DialogRequest();
        request.setSessionId("session-1");
        request.setInput("Привет");

        when(llmService.processDialog(any(), anyString())).thenThrow(new RuntimeException("kaboom"));

        ResponseEntity<DialogResponse> response = controller.dialog(request, null, null);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    }

    // ── /session/{id} ─────────────────────────────────────────────────

    @Test
    void clearSessionDelegatesToServiceAndReturnsOk() {
        ResponseEntity<Void> response = controller.clearSession("session-1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(llmService).clearSession("session-1");
    }

    // ── /health ───────────────────────────────────────────────────────

    private Map<String, Object> runtimeDescribe(boolean llmAvailable, boolean memoryEnabled, boolean memoryAvailable) {
        Map<String, Object> llm = Map.of(
                "available", llmAvailable,
                "baseUrl", "http://host-model-daemon:18080",
                "configuredProvider", "llama-cpp",
                "effectiveProvider", "llama-cpp",
                "configuredModel", "qwen",
                "effectiveModel", "qwen");
        Map<String, Object> memory = Map.of(
                "enabled", memoryEnabled,
                "serviceEnabled", memoryEnabled,
                "available", memoryAvailable);
        Map<String, Object> routing = Map.of(
                "hostDaemonHealthUrl", "http://host-model-daemon:18080/health",
                "llamaCppChatCompletionsUrl", "http://host-model-daemon:18080/v1/chat/completions");
        return Map.of(
                "llm", llm,
                "memory", memory,
                "routing", routing,
                "fullLocalAiReadiness", llmAvailable && memoryAvailable,
                "localModelProfile", "desktop-general");
    }

    @Test
    void healthReturnsOkWhenReady() {
        when(aiRuntimeStatusService.describe()).thenReturn(runtimeDescribe(true, true, true));
        when(lifecycleManager.getState()).thenReturn(LlmLifecycleManager.State.READY);
        when(lifecycleManager.isUsable()).thenReturn(true);
        when(lifecycleManager.isWarmupComplete()).thenReturn(true);
        when(lifecycleManager.getStateReason()).thenReturn("all systems operational");

        ResponseEntity<Map<String, Object>> response = controller.health();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("healthy", response.getBody().get("status"));
        verify(lifecycleManager).refreshState();
    }

    @Test
    void healthReturnsOkButDegradedWhenUsableNotReady() {
        when(aiRuntimeStatusService.describe()).thenReturn(runtimeDescribe(true, true, false));
        when(lifecycleManager.getState()).thenReturn(LlmLifecycleManager.State.DEGRADED);
        when(lifecycleManager.isUsable()).thenReturn(true);
        when(lifecycleManager.isWarmupComplete()).thenReturn(true);
        when(lifecycleManager.getStateReason()).thenReturn("memory-service unavailable");

        ResponseEntity<Map<String, Object>> response = controller.health();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("degraded", response.getBody().get("status"));
    }

    @Test
    void healthReturnsServiceUnavailableWhenNotUsable() {
        when(aiRuntimeStatusService.describe()).thenReturn(runtimeDescribe(false, false, false));
        when(lifecycleManager.getState()).thenReturn(LlmLifecycleManager.State.ERROR);
        when(lifecycleManager.isUsable()).thenReturn(false);
        when(lifecycleManager.isWarmupComplete()).thenReturn(false);
        when(lifecycleManager.getStateReason()).thenReturn("host-model-daemon unavailable");

        ResponseEntity<Map<String, Object>> response = controller.health();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("error", response.getBody().get("status"));
    }
}
