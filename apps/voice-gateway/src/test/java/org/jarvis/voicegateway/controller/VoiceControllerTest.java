package org.jarvis.voicegateway.controller;

import org.jarvis.voicegateway.client.OrchestratorClient;
import org.jarvis.voicegateway.service.SttService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.ByteArrayInputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoiceControllerTest {

    @Mock
    private SttService sttService;

    @Mock
    private OrchestratorClient orchestratorClient;

    @InjectMocks
    private VoiceController controller;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void transcribeStreamReturnsStructuredResponseAndForwardingState() throws Exception {
        when(sttService.transcribe(any(), eq("en-US")))
                .thenReturn("сделай громче");
        when(sttService.describeRuntime())
                .thenReturn(Map.of("configuredProvider", "vosk", "status", "available", "available", true));

        ResponseEntity<Map<String, Object>> response = controller.transcribeStream(
                new ByteArrayInputStream(new byte[] {1, 2, 3}),
                "en-US");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("сделай громче", response.getBody().get("text"));
        assertEquals("en-US", response.getBody().get("languageCode"));
        assertEquals(true, response.getBody().get("forwardedToOrchestrator"));
        assertEquals("available", ((Map<?, ?>) response.getBody().get("stt")).get("status"));
        verify(orchestratorClient).sendCommand("сделай громче", null);
    }

    @Test
    void transcribeStreamReturns422WhenSpeechIsNotRecognized() throws Exception {
        when(sttService.transcribe(any(), eq("ru-RU")))
                .thenReturn("");
        when(sttService.describeRuntime())
                .thenReturn(Map.of("configuredProvider", "vosk", "status", "available", "available", true));

        ResponseEntity<Map<String, Object>> response = controller.transcribeStream(
                new ByteArrayInputStream(new byte[] {4, 5}),
                null);

        assertEquals(422, response.getStatusCode().value());
        assertEquals("NO_SPEECH_RECOGNIZED", response.getBody().get("errorCode"));
        assertEquals("ru-RU", response.getBody().get("languageCode"));
        assertEquals(false, response.getBody().get("forwardedToOrchestrator"));
    }

    @Test
    void processTextCommandPropagatesDelegatedUserId() {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken("user-42", null,
                        java.util.List.of(new SimpleGrantedAuthority("ROLE_USER")));
        authentication.setDetails("delegated-by:api-gateway");
        SecurityContextHolder.getContext().setAuthentication(authentication);

        when(orchestratorClient.sendCommandWithResponse("сделай громче", "user-42"))
                .thenReturn("Готово, сэр.");

        String response = controller.processTextCommand(new VoiceController.TextCommandRequest("сделай громче"));

        assertEquals("Готово, сэр.", response);
        verify(orchestratorClient).sendCommandWithResponse("сделай громче", "user-42");
    }

    @Test
    void transcribeStreamPropagatesDelegatedUserId() throws Exception {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken("user-42", null,
                        java.util.List.of(new SimpleGrantedAuthority("ROLE_USER")));
        authentication.setDetails("delegated-by:api-gateway");
        SecurityContextHolder.getContext().setAuthentication(authentication);

        when(sttService.transcribe(any(), eq("en-US")))
                .thenReturn("сделай громче");
        when(sttService.describeRuntime())
                .thenReturn(Map.of("configuredProvider", "vosk", "status", "available", "available", true));

        ResponseEntity<Map<String, Object>> response = controller.transcribeStream(
                new ByteArrayInputStream(new byte[] {9, 8, 7}),
                "en-US");

        assertEquals(200, response.getStatusCode().value());
        verify(orchestratorClient).sendCommand("сделай громче", "user-42");
    }
}
