package org.jarvis.apigateway.controller;

import org.jarvis.apigateway.client.VoiceGatewayClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoiceProxyControllerTest {

    @Mock
    private VoiceGatewayClient voiceGatewayClient;

    @InjectMocks
    private VoiceProxyController controller;

    @Test
    void transcribeStreamDelegatesLanguageToVoiceGatewayClient() {
        byte[] audio = "pcm".getBytes(StandardCharsets.UTF_8);
        Map<String, Object> payload = Map.of("success", true, "languageCode", "en-US");
        when(voiceGatewayClient.transcribeStream(audio, "en-US")).thenReturn(ResponseEntity.ok(payload));

        ResponseEntity<Map<String, Object>> response = controller.transcribeStream(audio, "en-US");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("en-US", response.getBody().get("languageCode"));
        verify(voiceGatewayClient).transcribeStream(audio, "en-US");
    }

    @Test
    void commandDelegatesToVoiceGatewayClient() {
        Map<String, String> request = Map.of("text", "hello jarvis");
        when(voiceGatewayClient.command(request)).thenReturn(ResponseEntity.ok("Assistant reply"));

        ResponseEntity<String> response = controller.command("smoke-voice-1", request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("Assistant reply", response.getBody());
        verify(voiceGatewayClient).command(request);
    }

    @Test
    void synthesizeDelegatesToVoiceGatewayClientAndPreservesHeaders() {
        Map<String, Object> request = Map.of("text", "Привет");
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Jarvis-Tts-Status", "degraded");
        headers.add("X-Jarvis-Tts-Actual-Provider", "espeak");
        byte[] audio = new byte[] {1, 2, 3};
        when(voiceGatewayClient.synthesize(request)).thenReturn(ResponseEntity.ok().headers(headers).body(audio));

        ResponseEntity<byte[]> response = controller.synthesize(request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("degraded", response.getHeaders().getFirst("X-Jarvis-Tts-Status"));
        assertEquals("espeak", response.getHeaders().getFirst("X-Jarvis-Tts-Actual-Provider"));
        verify(voiceGatewayClient).synthesize(request);
    }

    @Test
    void runtimeDelegatesToVoiceGatewayClient() {
        Map<String, Object> runtime = Map.of("status", "partial");
        when(voiceGatewayClient.runtime()).thenReturn(ResponseEntity.ok(runtime));

        ResponseEntity<Map<String, Object>> response = controller.runtime();

        assertEquals(200, response.getStatusCode().value());
        assertEquals("partial", response.getBody().get("status"));
        verify(voiceGatewayClient).runtime();
    }
}
