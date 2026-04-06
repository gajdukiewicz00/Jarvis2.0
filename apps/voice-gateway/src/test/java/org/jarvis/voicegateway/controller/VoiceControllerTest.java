package org.jarvis.voicegateway.controller;

import org.jarvis.voicegateway.client.OrchestratorClient;
import org.jarvis.voicegateway.service.SttService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

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
        verify(orchestratorClient).sendCommand("сделай громче");
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
}
